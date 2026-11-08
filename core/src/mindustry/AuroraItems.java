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
import static mindustry.AuroraNet.js;
import static mindustry.AuroraNet.q;
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
        StringBuilder items = new StringBuilder("{");
        for (var e : need) {
            if (items.length() > 1) items.append(',');
            items.append(js(e.key.name)).append(':').append(e.value);
        }
        String extra = research == null ? "" : ",\"research\":" + js(research) + ",\"by\":" + js(myName());
        return "{\"id\":" + js(java.util.UUID.randomUUID().toString()) + ",\"items\":" + items.append('}') + extra + "}";
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
        StringBuilder sb = new StringBuilder("{");
        var core = state.rules.defaultTeam.core();
        if (core != null) {
            for (Item it : content.items()) {
                int a = core.items.get(it);
                if (a <= 0) continue;
                if (sb.length() > 1) sb.append(',');
                sb.append(js(it.name)).append(':').append(a);
            }
        }
        return sb.append('}').toString();
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
                            if (appliedOps.size > 2000) appliedOps.clear();
                            for (JsonValue c = arr.child; c != null; c = c.next) {
                                String opId = c.getString("id");
                                Item it = content.item(c.getString("item"));
                                // Take each op only once, but always ack it again — Кожне списання знімаємо лише раз, але підтверджуємо щоразу
                                // Op ids repeat across sectors (one spend split over several), so key by sector too — id списань повторюються між секторами (одна витрата на кілька), тож ключ ще й за сектором
                                if (appliedOps.add(sid(s) + "/" + opId) && it != null && core != null) {
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
                        uploads.submit(() -> {
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
                                    putBytes(hostPath(s, "save") + "&ops=" + q(acked.toString()), pack[0]);
                                    putBytes(hostPath(s, "info"), pack[1]);
                                    Log.info("Aurora: sector @ saved to Authority (spend)", sid(s));
                                } else if (acked.length() > 0) {
                                    Res a = post(hostPath(s, "ack") + "&ids=" + q(acked.toString()));
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