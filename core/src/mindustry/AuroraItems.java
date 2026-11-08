package mindustry;
import arc.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.type.*;

import static mindustry.Vars.*;
import static mindustry.AuroraNet.current;
import static mindustry.AuroraNet.hosting;
import static mindustry.AuroraNet.itemsActive;
import static mindustry.AuroraUtil.urlEnc;
import static mindustry.AuroraNet.sid;
import static mindustry.AuroraNet.get;
import static mindustry.AuroraNet.post;
import static mindustry.AuroraNet.putBytes;
import static mindustry.AuroraNet.parse;
import static mindustry.AuroraNet.request;
import mindustry.AuroraNet.Res;
import java.nio.charset.StandardCharsets;

import static mindustry.AuroraNet.myName;
import static mindustry.AuroraNet.hostPath;
import static mindustry.AuroraNet.snapshot;
import static mindustry.AuroraNet.uploads;
import static mindustry.AuroraNet.lastPush;
import static mindustry.AuroraNet.forcePush;
public class AuroraItems {

    // ---------- Shared items — Спільні ресурси ----------
    /** A stock sync is in flight; skip overlapping ones — Синхронізація складу вже йде; накладені пропускаємо. */
    private static volatile boolean stockBusy;
    static volatile ItemSeq shared = new ItemSeq();

    /** New session: forget the old planet's stock — Нова сесія: забути склад старої планети. */
    static void reset() {
        shared = new ItemSeq();
    }

    /** Shared stock as last seen from Authority — Спільний склад, яким його востаннє бачив Authority. */
    public static ItemSeq globalItems() {
        return shared.copy();
    }

    public static void refreshItems() {
        refreshItems(false);
    }

    /** fresh = Authority first asks the sector hosts for their current stock (≤1.5 s) — fresh = Authority спершу просить у хостів секторів їхній поточний склад (≤1.5 с). */
    public static void refreshItems(boolean fresh) {
        if (!itemsActive()) return;
        mainExecutor.submit(() -> {
            try {
                Res res = get(fresh ? "/items?fresh=1" : "/items");
                if (res.code != 200) return;
                ItemSeq seq = new ItemSeq();
                for (JsonValue c = parse(res).child; c != null; c = c.next) {
                    Item it = content.item(c.name);
                    if (it != null) seq.set(it, c.asInt());
                }
                Core.app.post(() -> {
                    // Rebuild the research items only when the counts changed — Перебудовуємо ресурси в дереві лише коли кількості змінились
                    boolean changed = !sameItems(shared, seq);
                    shared = seq;
                    if (changed && ui.research.isShown()) ui.research.rebuildItems();
                });
            } catch (Throwable t) {
                Log.err("refreshItems failed", t);
            }
        });
    }

    static boolean sameItems(ItemSeq a, ItemSeq b) {
        if (a == null || b == null) return a == b;
        for (Item it : content.items()) if (a.get(it) != b.get(it)) return false;
        return true;
    }

    /**
     * Confirmed spend: done(true) only if Authority accepted it (200). 409 = not enough, any error = no connection.
     * done runs on the main thread.
     * — Списання з підтвердженням: done(true) лише якщо Authority прийняв (200). 409 = не вистачає, помилка = нема зв'язку.
     * done виконується на головному потоці.
     */
    static String spendBody(ObjectIntMap<Item> need) {
        return spendBody(need, null);
    }

    /** research = content being paid for: Authority checks the full cost and opens it — research = те, за що платимо: Authority перевіряє повну ціну й відкриває його. */
    static String spendBody(ObjectIntMap<Item> need, String research) {
        AuroraJson items = AuroraJson.obj();
        for (var e : need) items.put(e.key.name, e.value);
        AuroraJson o = AuroraJson.obj().put("id", java.util.UUID.randomUUID().toString()).put("items", items);
        if (research != null) o.put("research", research).put("by", myName());
        return o.toString();
    }

    /** Spend on the calling (background) thread: answer code, or -1 = no connection — Списання в поточному (фоновому) потоці: код відповіді, або -1 = нема зв'язку. */
    static int spendBlocking(ObjectIntMap<Item> need) {
        try {
            Res res = request("POST", "/spend", spendBody(need).getBytes(StandardCharsets.UTF_8));
            if (res.code != 200) Log.warn("spend rejected: @", res.code);
            return res.code;
        } catch (Exception e) {
            Log.err("spend failed", e);
            return -1;
        }
    }

    public static void spendConfirmed(ObjectIntMap<Item> need, String research, Boolc done) {
        String body = spendBody(need, research);
        mainExecutor.submit(() -> {
            boolean ok = false;
            try {
                Res res = request("POST", "/spend", body.getBytes(StandardCharsets.UTF_8));
                ok = res.code == 200;
                if (!ok) Log.warn("spend rejected: @", res.code);
            } catch (Exception e) {
                Log.err("spend failed", e);
            }
            boolean good = ok;
            Core.app.post(() -> done.get(good));
        });
    }

    /** Our core items as JSON (main thread) — Ресурси нашого ядра в JSON (головний потік). */
    static String stockJson() {
        AuroraJson o = AuroraJson.obj();
        var core = state.rules.defaultTeam.core();
        if (core != null) {
            for (Item it : content.items()) {
                int a = core.items.get(it);
                if (a > 0) o.put(it.name, a);
            }
        }
        return o.toString();
    }

    /**
     * Host: take queued spends, remove them from our core, ack, push the fresh stock. Runs every beat.
     * — Хост: забрати чергу списань, зняти з ядра, підтвердити, відправити свіжий склад. Кожен beat.
     */
    /**
     * Spend ops this host already took from its core. If an ack is lost, Authority sends the same op again;
     * without this set every sync would take it again and drain the core.
     * — Списання, які цей хост уже зняв з ядра. Якщо підтвердження загубилось, Authority надішле те саме знову;
     * без цього набору кожна синхронізація знімала б його ще раз і спустошувала ядро.
     */
    static final ObjectSet<String> appliedOps = new ObjectSet<>();
    /**
     * appliedOps in the order they were added; past APPLIED_KEEP the oldest are forgotten one by one. Clearing the whole set
     * (as before) could forget an op that was taken from the core but not yet acked, and take it a second time.
     * — appliedOps у порядку додавання; понад APPLIED_KEEP найстаріші забуваються по одному. Очищення всього набору
     * (як раніше) могло забути списання, вже зняте з ядра, але ще не підтверджене, і зняти його вдруге.
     */
    static final Queue<String> appliedOrder = new Queue<>();
    static final int APPLIED_KEEP = 2000;

    /**
     * Plain stock pushes (no new spends, nothing to ack) go through their own thread, so they never wait behind a save of
     * several MB in the upload queue. Pushes that ack spends stay in the upload queue, in order with the saves.
     * — Звичайні пуші складу (без нових списань і без підтверджень) мають свій потік, тож ніколи не чекають за сейвом
     * на кілька МБ у черзі заливання. Пуші з підтвердженням списань лишаються в черзі заливання, по порядку із сейвами.
     */
    static final java.util.concurrent.ExecutorService stockUploads = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-stock");
        t.setDaemon(true);
        return t;
    });

    static void syncStock(Sector s) {
        if (stockBusy) return;
        stockBusy = true;
        mainExecutor.submit(() -> {
            try {
                Res r = get(hostPath(s, "pending"));
                JsonValue arr = r.code == 200 ? parse(r) : null;
                Core.app.post(() -> {
                    try {
                        if (!hosting || current != s || !state.isGame() || state.rules.sector != s) {
                            stockBusy = false;
                            return;
                        }
                        var core = state.rules.defaultTeam.core();
                        StringBuilder acked = new StringBuilder();
                        boolean appliedNew = false;
                        if (arr != null) {
                            for (JsonValue c = arr.child; c != null; c = c.next) {
                                String opId = c.getString("id");
                                Item it = content.item(c.getString("item"));
                                // Take each op only once, but always ack it again — Кожне списання знімаємо лише раз, але підтверджуємо щоразу
                                // Op ids repeat across sectors (one spend split over several), so key by sector too — id списань повторюються між секторами (одна витрата на кілька), тож ключ ще й за сектором
                                String key = sid(s) + "/" + opId;
                                boolean first = appliedOps.add(key);
                                if (first) {
                                    appliedOrder.addLast(key);
                                    while (appliedOrder.size > APPLIED_KEEP) appliedOps.remove(appliedOrder.removeFirst());
                                }
                                if (first && it != null && core != null) {
                                    core.items.remove(it, Math.min(c.getInt("amount"), core.items.get(it)));
                                    appliedNew = true;
                                }
                                if (acked.length() > 0) acked.append(',');
                                acked.append(opId);
                            }
                        }
                        String body = stockJson();
                        // New spends taken from the core: snapshot now, the save carries their ack (see below)
                        // — Нові списання зняті з ядра: знімок зараз, сейв несе їхнє підтвердження (див. нижче)
                        byte[][] pack = appliedNew ? snapshot(s) : null;
                        if (pack != null) lastPush = Time.millis();
                        boolean needSave = appliedNew;
                        // Acks and saves keep their order in the upload queue; a plain stock push takes the fast lane
                        // — Підтвердження й сейви тримають порядок у черзі заливання; звичайний пуш складу йде швидкою смугою
                        (needSave || acked.length() > 0 ? uploads : stockUploads).submit(() -> {
                            // Order: stock -> then the ack. With new spends the ack rides on the save that already has them
                            // (one request), so a crash can neither refund them nor charge them twice. Any failure: no ack,
                            // the next sync repeats it (appliedOps keeps the core from being charged twice).
                            // — Порядок: склад -> потім підтвердження. З новими списаннями підтвердження їде разом із сейвом, де вони вже є
                            // (один запит), тож падіння не поверне їх і не зніме двічі. Будь-яка помилка: без підтвердження,
                            // наступна синхронізація повторить (appliedOps не дасть зняти з ядра двічі).
                            try {
                                putBytes(hostPath(s, "items"), body.getBytes(StandardCharsets.UTF_8));
                                if (needSave) {
                                    if (pack == null) throw new RuntimeException("no snapshot");
                                    putBytes(hostPath(s, "save") + "&ops=" + urlEnc(acked.toString()), pack[0]);
                                    putBytes(hostPath(s, "info"), pack[1]);
                                    Log.info("Aurora: sector @ saved to Authority (spend)", sid(s));
                                } else if (acked.length() > 0) {
                                    Res a = post(hostPath(s, "ack") + "&ids=" + urlEnc(acked.toString()));
                                    if (a.code != 200) Log.warn("Aurora: ack rejected @", a.code);
                                }
                            } catch (Exception e) {
                                Log.err("syncStock upload failed", e);
                                if (needSave) forcePush = true;
                            } finally {
                                stockBusy = false;
                            }
                        });
                    } catch (Throwable t) {
                        // Never leave the flag stuck, or the host would stop syncing — Ніколи не лишати прапорець, інакше хост перестане синхронізуватися
                        stockBusy = false;
                        Log.err("syncStock apply failed", t);
                    }
                });
            } catch (Throwable t) {
                stockBusy = false;
                Log.err("syncStock failed", t);
            }
        });
    }


}