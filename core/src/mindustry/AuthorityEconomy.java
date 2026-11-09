package mindustry;

import arc.*;
import arc.files.*;
import arc.util.*;
import arc.util.serialization.*;
import arc.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

import static mindustry.AuroraAuthority.dir;
import static mindustry.AuroraAuthority.server;
import static mindustry.AuroraAuthority.writeAtomic;
import static mindustry.AuthorityAccess.fromThisPc;
import static mindustry.AuthorityClaims.Host;
import static mindustry.AuthorityClaims.hosts;
import static mindustry.AuthorityClaims.infos;
import static mindustry.AuthorityClaims.putInfo;
import static mindustry.AuthorityResearch.parentOpen;
import static mindustry.AuthorityResearch.researchCost;
import static mindustry.AuthorityResearch.saveUnlocks;
import static mindustry.AuthorityResearch.unlocked;
import static mindustry.AuthorityResearch.unlockedBy;
import static mindustry.AuthorityRoutes.Exchange;
import static mindustry.AuthorityRoutes.readBody;
import static mindustry.AuthorityRoutes.sector;
import static mindustry.AuthorityRoutes.send;

/**
 * Authority internals: the shared item stock: sector stocks, research payments split across sectors, instant sync (long poll), offline production.
 * — Нутрощі Authority: спільний склад: склади секторів, оплата досліджень з розподілом по секторах, миттєва синхронізація (long poll), виробництво без гравців.
 */
final class AuthorityEconomy {

    private AuthorityEconomy() {
    }

    // ---------- Host lifecycle — Життєвий цикл хоста ----------

    /**
     * Split a payment across sectors in proportion to their stock. avail: item -> sector -> amount (> 0), and every
     * item's total is already known to cover need. Returns item -> sector -> amount to take; per item the amounts add
     * up to need, and no sector gives more than it has.
     * — Розкласти оплату по секторах пропорційно їхньому запасу. avail: предмет -> сектор -> кількість (> 0), і сума по
     * кожному предмету вже точно покриває need. Повертає предмет -> сектор -> скільки зняти; по кожному предмету сума
     * дорівнює need, і жоден сектор не віддає більше, ніж має.
     */
    static Map<String, Map<String, Integer>> splitSpend(Map<String, Integer> need, Map<String, Map<String, Integer>> avail) {
        Map<String, Map<String, Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            Map<String, Integer> per = avail.get(e.getKey());
            Map<String, Integer> takes = new LinkedHashMap<>();
            out.put(e.getKey(), takes);
            if (per == null) continue;
            long total = 0;
            for (int a : per.values()) total += a;
            int left = e.getValue();
            for (Map.Entry<String, Integer> s : per.entrySet()) {
                if (left == 0) break;
                int take = (int) Math.min((long) Math.ceil((double) e.getValue() * s.getValue() / total), Math.min(left, s.getValue()));
                if (take <= 0) continue;
                takes.put(s.getKey(), take);
                left -= take;
            }
        }
        return out;
    }

    // ---------- Economy on disk — Економіка на диску ----------

    /**
     * economy.json: {"stock":{sector:{item:n}}, "pending":{sector:[{id,item,amount}]}}.
     * Sectors missing there get their stock from the last info ("items" = core at save time).
     * — Сектори, яких там нема, отримують склад з останнього info ("items" = ядро на момент збереження).
     */
    static void loadEconomy() {
        stock.clear();
        pending.clear();
        Fi f = dir().child("economy.json");
        if (f.exists()) {
            try {
                JsonValue root = new JsonReader().parse(f.readString());
                JsonValue st = root.get("stock");
                if (st != null) for (JsonValue sec = st.child; sec != null; sec = sec.next) stock.put(sec.name, readCounts(sec));
                JsonValue pd = root.get("pending");
                if (pd != null) for (JsonValue sec = pd.child; sec != null; sec = sec.next) {
                    CopyOnWriteArrayList<Op> list = new CopyOnWriteArrayList<>();
                    for (JsonValue o = sec.child; o != null; o = o.next) list.add(new Op(o.getString("id"), o.getString("item"), o.getInt("amount")));
                    if (!list.isEmpty()) pending.put(sec.name, list);
                }
            } catch (Throwable e) {
                Log.err("Aurora economy.json load failed", e);
            }
        }
        infos.forEach((id, info) -> {
            if (stock.containsKey(id)) return;
            try {
                JsonValue items = new JsonReader().parse(info).get("items");
                if (items == null) return;
                Map<String, Integer> m = readCounts(items);
                if (!m.isEmpty()) stock.put(id, m);
            } catch (Throwable ignored) {
            }
        });
        Log.info("Aurora economy: @ sectors with stock, @ with pending ops", stock.size(), pending.size());
    }

    /** {"item": n, ...} -> map, numbers only — {"предмет": n, ...} -> map, лише числа. */
    static Map<String, Integer> readCounts(JsonValue obj) {
        Map<String, Integer> m = new ConcurrentHashMap<>();
        for (JsonValue it = obj.child; it != null; it = it.next) if (it.isNumber()) m.put(it.name, it.asInt());
        return m;
    }

    // ---------- Stock sanity — Перевірка складу ----------

    /** No core holds more than this per item (nucleus + many vaults is far below) — Жодне ядро не тримає більше на один предмет (ядро + багато сховищ — значно менше). */
    static final int STORAGE_MAX = 200_000;

    /** Core capacity of a sector from its last info, bounded; STORAGE_MAX if unknown — Місткість ядра сектора з останнього info, з межею; STORAGE_MAX, якщо невідомо. */
    static int capacityOf(String id) {
        String js = infos.get(id);
        if (js == null) return STORAGE_MAX;
        try {
            int c = new JsonReader().parse(js).getInt("storageCapacity", STORAGE_MAX);
            return c <= 0 ? STORAGE_MAX : Math.min(c, STORAGE_MAX);
        } catch (Throwable e) {
            return STORAGE_MAX;
        }
    }

    /** A sector host's reported stock, made sane: only real items, 0..core capacity. Silent — Склад, який повідомив хост сектора, приведений до норми: лише справжні предмети, 0..місткість ядра. Мовчки. */
    static Map<String, Integer> sanitizeStock(String id, Map<String, Integer> in) {
        int cap = capacityOf(id);
        Map<String, Integer> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, Integer> e : in.entrySet()) {
            if (Vars.content.item(e.getKey()) == null) continue;
            int v = Math.max(0, Math.min(cap, e.getValue()));
            if (v > 0) out.put(e.getKey(), v);
        }
        return out;
    }

    static void saveEconomy() {
        // Same lock as before the split (AuroraAuthority.class): these writes must not interleave with each other
        // — Той самий замок, що й до поділу (AuroraAuthority.class): ці записи не мають перемішуватись між собою
        synchronized (AuroraAuthority.class) {
            try {
                AuroraJson st = AuroraJson.obj();
                for (Map.Entry<String, Map<String, Integer>> e : stock.entrySet()) st.putRaw(e.getKey(), countsJson(e.getValue()));
                AuroraJson pe = AuroraJson.obj();
                for (Map.Entry<String, CopyOnWriteArrayList<Op>> e : pending.entrySet()) {
                    if (e.getValue().isEmpty()) continue;
                    pe.putRaw(e.getKey(), opsJson(e.getValue()));
                }
                // Temp file first, then replace: a crash never leaves a cut JSON — Спершу тимчасовий файл, потім заміна: краш не лишить обрізаний JSON
                writeAtomic(dir().child("economy.json"), AuroraJson.obj().put("stock", st).put("pending", pe).toString());
            } catch (Throwable e) {
                Log.err("Aurora economy save failed", e);
            }
        }
    }

    // ---------- Offline production — Виробництво без гравців ----------

    /** Production step: small, so the shared stock grows smoothly; disk writes are batched (DISK_EVERY_MS)
     * — Крок виробництва: малий, щоб спільний склад ріс плавно; запис на диск — пачками (DISK_EVERY_MS). */
    static final float PRODUCE_SECONDS = 5f;

    static final long DISK_EVERY_MS = 60_000L;

    static final Set<String> produceDirty = new HashSet<>();

    static long lastProduceDisk;

    /** Fractions below one item carried to the next tick: "sector/item" -> rest — Дробові залишки до наступного тіку: "сектор/предмет" -> залишок. */
    static final Map<String, Float> produceRest = new HashMap<>();

    /** Highest believable net production of one item, items/s — Найбільше правдоподібне чисте виробництво одного предмета, шт/с. */
    static final float MAX_RATE = 1000f;

    /**
     * Sectors nobody plays keep producing like in the vanilla campaign: net production (info.production) x time x (1 - damage),
     * capped by core capacity. Goes into the sector info (core items when someone loads it) and into the shared stock.
     * Time passes only while this Authority runs. Runs on the sweeper thread and reads the info as a plain JSON tree
     * (no JsonIO, which belongs to the main thread).
     * — Сектори, де ніхто не грає, виробляють як у ванільній кампанії: чисте виробництво (info.production) x час x (1 - пошкодження),
     * не більше місткості ядра. Йде в info сектора (предмети ядра, коли хтось його завантажить) і в спільний склад.
     * Час іде лише поки працює цей Authority. Працює в потоці прибиральника й читає info як просте JSON-дерево
     * (без JsonIO, який належить головному потоку).
     */
    static void produce(float secs) {
        // Same lock as before the split (AuroraAuthority.class): these writes must not interleave with each other
        // — Той самий замок, що й до поділу (AuroraAuthority.class): ці записи не мають перемішуватись між собою
        synchronized (AuroraAuthority.class) {
            if (server == null) return;
            long now = System.currentTimeMillis();
            boolean any = false;
            for (String id : new ArrayList<>(infos.keySet())) {
                Host h = hosts.get(id);
                if (h != null && h.alive(now)) continue; // someone plays: his core counts itself — хтось грає: його ядро рахує саме
                String js = infos.get(id);
                if (js == null) continue;
                try {
                    JsonValue root = new JsonReader().parse(js);
                    JsonValue prod = root.get("production");
                    if (!root.getBoolean("hasCore", true) || prod == null || prod.child == null) continue;
                    float scl = Math.max(1f - root.getFloat("damage", 0f), 0f);
                    int cap = Math.max(0, Math.min(root.getInt("storageCapacity", 0), STORAGE_MAX));
                    JsonValue items = root.get("items");
                    if (items == null) {
                        items = new JsonValue(JsonValue.ValueType.object);
                        root.addChild("items", items);
                    }
                    Map<String, Integer> st = stock.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
                    boolean changed = false;
                    for (JsonValue e = prod.child; e != null; e = e.next) {
                        if (Vars.content.item(e.name) == null) continue;
                        float mean = Math.max(-MAX_RATE, Math.min(MAX_RATE, e.getFloat("mean", 0f)));
                        if (mean == 0f) continue;
                        String key = id + "/" + e.name;
                        float amount = mean * secs * scl + produceRest.getOrDefault(key, 0f);
                        int whole = (int) amount;
                        produceRest.put(key, amount - whole);
                        if (whole == 0) continue;
                        JsonValue iv = items.get(e.name);
                        int have = iv == null ? 0 : iv.asInt();
                        int add = Math.max(Math.min(whole, cap - have), -have);
                        if (add == 0) continue;
                        if (iv == null) items.addChild(e.name, new JsonValue(have + add));
                        else iv.set(have + add, null);
                        int sv = st.getOrDefault(e.name, 0);
                        st.put(e.name, Math.max(0, Math.min(cap, sv + add)));
                        changed = true;
                    }
                    if (!changed) continue;
                    String out = root.toJson(JsonWriter.OutputType.minimal);
                    // Only if the host did not push a newer info meanwhile — Лише якщо хост тим часом не відправив новіше info
                    if (!infos.replace(id, js, out)) continue;
                    putInfo(id, out);
                    produceDirty.add(id);
                    any = true;
                } catch (Throwable e) {
                    Log.err("Aurora production failed for " + id, e);
                }
            }
            if (any) {
                synchronized (stockLock) {
                    stockLock.notifyAll(); // /items?fresh=1 waiting — хтось чекає /items?fresh=1
                }
            }
            if (now - lastProduceDisk >= DISK_EVERY_MS) flushProduction();
        }
    }

    /** Writes produced sector infos and the stock to disk — Пише вироблене (info секторів і склад) на диск. */
    static void flushProduction() {
        // Same lock as before the split (AuroraAuthority.class): these writes must not interleave with each other
        // — Той самий замок, що й до поділу (AuroraAuthority.class): ці записи не мають перемішуватись між собою
        synchronized (AuroraAuthority.class) {
            lastProduceDisk = System.currentTimeMillis();
            if (produceDirty.isEmpty()) return;
            for (String id : produceDirty) {
                String s = infos.get(id);
                if (s == null) continue;
                try {
                    writeAtomic(dir().child(id + ".info"), s);
                } catch (IOException e) {
                    Log.err("Aurora production: writing " + id + ".info failed", e);
                }
            }
            produceDirty.clear();
            saveEconomy();
        }
    }

    // ---------- Shared items — Спільні ресурси ----------

    /** A spend op waiting for a sector host to apply it — Списання, що чекає, поки хост сектора його застосує. */
    static class Op {
        final String id, item;
        final int amount;

        Op(String id, String item, int amount) {
            this.id = id;
            this.item = item;
            this.amount = amount;
        }
    }

    static final ConcurrentHashMap<String, Map<String, Integer>> stock = new ConcurrentHashMap<>();

    static final ConcurrentHashMap<String, CopyOnWriteArrayList<Op>> pending = new ConcurrentHashMap<>();

    // ---------- Instant sync (long poll) — Миттєва синхронізація (long poll) ----------

    /** Per-sector wake counter; a host's /wait returns as soon as it grows — Лічильник пробудження сектора; /wait хоста повертається, щойно він зростає. */
    static final ConcurrentHashMap<String, long[]> wakeVer = new ConcurrentHashMap<>();

    /** When each sector last pushed its stock, ms — Коли кожен сектор востаннє відправив склад, мс. */
    static final ConcurrentHashMap<String, Long> stockTime = new ConcurrentHashMap<>();

    static final Object stockLock = new Object();

    /** Max time a host's /wait is held, ms (below the client's 15 s read timeout) — Найдовше утримання /wait хоста, мс (менше за 15 с тайм-аут читання клієнта). */
    static final long WAIT_MAX = 10000;

    /** How long /items?fresh=1 waits for hosts to push fresh stock, ms — Скільки /items?fresh=1 чекає, поки хости відправлять свіжий склад, мс. */
    static final long FRESH_MAX = 1500;

    /** Wake the host of a sector: it will apply pending spends and push its stock now — Розбудити хоста сектора: він одразу застосує списання й відправить склад. */
    static void wake(String id) {
        long[] v = wakeVer.computeIfAbsent(id, k -> new long[1]);
        long ver;
        List<Waiter> list;
        synchronized (v) {
            ver = ++v[0];
            list = waiters.remove(id);
        }
        if (list != null) for (Waiter w : list) w.ex.finish(200, AuroraJson.obj().put("ver", ver).toString().getBytes(StandardCharsets.UTF_8));
    }

    /** A host's parked /wait request — Запарковане /wait хоста. */
    static final class Waiter {
        final Exchange ex;
        final long deadline;

        Waiter(Exchange ex, long deadline) {
            this.ex = ex;
            this.deadline = deadline;
        }
    }

    static final ConcurrentHashMap<String, CopyOnWriteArrayList<Waiter>> waiters = new ConcurrentHashMap<>();

    /** Every second: answer parked /wait requests older than WAIT_MAX — Щосекунди: відповісти на запарковані /wait, старші за WAIT_MAX. */
    static void expireWaiters() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, CopyOnWriteArrayList<Waiter>> e : waiters.entrySet()) {
                long[] v = wakeVer.computeIfAbsent(e.getKey(), k -> new long[1]);
                for (Waiter w : e.getValue()) {
                    if (w.deadline > now && w.ex.conn.isConnected()) continue;
                    e.getValue().remove(w);
                    long ver;
                    synchronized (v) {
                        ver = v[0];
                    }
                    w.ex.finish(200, AuroraJson.obj().put("ver", ver).toString().getBytes(StandardCharsets.UTF_8));
                }
                // Drop the emptied list, under the same lock /wait adds under, so a waiter added right now is not lost
                // — Прибрати спорожнілий список під тим самим замком, під яким /wait додає, щоб щойно доданий не загубився
                synchronized (v) {
                    if (e.getValue().isEmpty()) waiters.remove(e.getKey(), e.getValue());
                }
            }
        } catch (Throwable t) {
            Log.err("Aurora: expireWaiters failed", t);
        }
    }

    /** Wake every live host and wait (up to FRESH_MAX) until they all pushed stock — Розбудити всіх живих хостів і почекати (до FRESH_MAX), поки всі відправлять склад. */
    static void freshStock() throws InterruptedException {
        long t0 = System.currentTimeMillis(), now = t0;
        List<String> live = new ArrayList<>();
        for (Map.Entry<String, Host> e : hosts.entrySet()) {
            if (e.getValue().alive(now)) live.add(e.getKey());
        }
        if (live.isEmpty()) return;
        for (String id : live) wake(id);
        synchronized (stockLock) {
            long left;
            while ((left = t0 + FRESH_MAX - System.currentTimeMillis()) > 0) {
                boolean all = true;
                for (String id : live) {
                    if (stockTime.getOrDefault(id, 0L) < t0) {
                        all = false;
                        break;
                    }
                }
                if (all) return;
                stockLock.wait(left);
            }
        }
    }

    static final CopyOnWriteArrayList<Op> NONE = new CopyOnWriteArrayList<>();

    /** Handled spend ids (id -> time), so a retried request never spends twice; older than 1 h are dropped
     * — Оброблені списання (id -> час), щоб повтор запиту не списав двічі; старші за годину видаляються. */
    static final ConcurrentHashMap<String, Long> doneOps = new ConcurrentHashMap<>();

    static final long DONE_OPS_TTL = 3_600_000L;

    /** Sector stock minus ops not yet applied — Запас сектора мінус ще не застосовані списання. */
    static int shown(String sector, String item) {
        Map<String, Integer> m = stock.get(sector);
        if (m == null) return 0;
        int v = m.getOrDefault(item, 0);
        for (Op o : pending.getOrDefault(sector, NONE)) if (o.item.equals(item)) v -= o.amount;
        return Math.max(v, 0);
    }

    /**
     * Research payment: all or nothing. 200 = accepted and split across sectors, 409 = not enough.
     * — Оплата дослідження: все або нічого. 200 = прийнято й розкладено по секторах, 409 = не вистачає.
     */
    static void spend(Exchange ex) throws IOException {
        // Same lock as before the split (AuroraAuthority.class): these writes must not interleave with each other
        // — Той самий замок, що й до поділу (AuroraAuthority.class): ці записи не мають перемішуватись між собою
        synchronized (AuroraAuthority.class) {
            JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
            String opId = j.getString("id", "");
            // A comma or odd chars in the id would make an op that can never be acked — Кома чи дивні символи в id дали б списання, яке ніколи не підтвердиться
            if (!opId.matches("[A-Za-z0-9\\-]{1,64}")) {
                send(ex, 400, "");
                return;
            }
            // Validate everything first, remember the id only after: a broken request must not mark its id as done
            // — Спершу все перевіряємо, id запам'ятовуємо лише потім: зламаний запит не має позначити свій id виконаним
            JsonValue itemsJson = j.get("items");
            if (itemsJson == null) {
                send(ex, 400, "");
                return;
            }
            Map<String, Integer> need = new HashMap<>();
            for (JsonValue c = itemsJson.child; c != null; c = c.next) {
                if (!c.isNumber() || Vars.content.item(c.name) == null) {
                    send(ex, 400, "");
                    return;
                }
                if (c.asInt() > 0) need.put(c.name, c.asInt());
            }

            // Paying for research: the spend must cover the node's full cost and the node's parent must be open;
            // it opens right here after payment
            // — Оплата дослідження: списання має покривати повну ціну вузла, а батьківський вузол має бути відкритий;
            // воно відкривається тут же після оплати
            String research = j.getString("research", "");
            String by = j.getString("by", "");
            if (by.length() > 40) by = by.substring(0, 40);
            if (!research.isEmpty()) {
                Map<String, Integer> cost = researchCost(research);
                if (cost == null) {
                    send(ex, 400, "");
                    return;
                }
                if (!fromThisPc(ex)) {
                    for (Map.Entry<String, Integer> c : cost.entrySet()) {
                        if (need.getOrDefault(c.getKey(), 0) < c.getValue()) {
                            send(ex, 400, ""); // pays less than the research costs — платить менше, ніж коштує дослідження
                            return;
                        }
                    }
                    if (!parentOpen(research)) {
                        send(ex, 400, ""); // out of tree order — не по порядку дерева
                        return;
                    }
                }
            }

            long now = System.currentTimeMillis();
            doneOps.values().removeIf(t -> now - t > DONE_OPS_TTL);
            if (doneOps.putIfAbsent(opId, now) != null) { // retry of a handled request — повтор уже обробленого запиту
                send(ex, 200, "");
                return;
            }

            // Available per item per sector, computed once — Доступно по кожному предмету й сектору, рахуємо один раз
            Map<String, Map<String, Integer>> avail = new HashMap<>();
            for (Map.Entry<String, Integer> e : need.entrySet()) {
                Map<String, Integer> per = new HashMap<>();
                int total = 0;
                for (String sec : stock.keySet()) {
                    int a = shown(sec, e.getKey());
                    if (a > 0) per.put(sec, a);
                    total += a;
                }
                if (total < e.getValue()) {
                    doneOps.remove(opId);
                    send(ex, 409, "");
                    return;
                }
                avail.put(e.getKey(), per);
            }

            // Split proportionally to each sector's stock, like vanilla — Розкладаємо пропорційно запасу секторів, як у ванілі
            for (Map.Entry<String, Map<String, Integer>> e : splitSpend(need, avail).entrySet()) {
                String item = e.getKey();
                for (Map.Entry<String, Integer> s : e.getValue().entrySet()) {
                    pending.computeIfAbsent(s.getKey(), k -> new CopyOnWriteArrayList<>()).add(new Op(opId + ":" + item, item, s.getValue()));
                }
            }
            saveEconomy();
            if (!research.isEmpty() && unlocked.add(research)) {
                if (!by.isEmpty()) unlockedBy.put(research, by);
                saveUnlocks();
            }
            // Hosts take the spend from their cores right away — Хости одразу знімають списання зі своїх ядер
            for (Map<String, Integer> per : avail.values()) {
                for (String sec : per.keySet()) wake(sec);
            }
            send(ex, 200, "");
        }
    }

    // ---------- Flood limits (this PC is exempt) — Обмеження від флуду (цей ПК не обмежується) ----------

    static String countsJson(Map<String, Integer> m) {
        AuroraJson o = AuroraJson.obj();
        m.forEach((k, v) -> o.put(k, v));
        return o.toString();
    }

    static String opsJson(List<Op> ops) {
        AuroraJson a = AuroraJson.arr();
        for (Op o : ops) a.add(AuroraJson.obj().put("id", o.id).put("item", o.item).put("amount", o.amount));
        return a.toString();
    }
}
