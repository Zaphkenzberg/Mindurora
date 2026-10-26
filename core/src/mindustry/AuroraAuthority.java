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

/**
 * Built-in Authority: the "brain" of a shared Aurora planet, an ArcNet (TCP) server inside the planet host's game.
 * A request is method, path, token, protocol and body; the answer is a code and a body (see AuroraWire).
 * Keeps who hosts which sector, sector saves, shared research and the shared item stock.
 * — Вбудований Authority: "мозок" спільної планети Aurora, сервер ArcNet (TCP) у грі хоста планети.
 * Тримає, хто хостить який сектор, сейви секторів, спільні дослідження і спільний склад.
 */
public class AuroraAuthority {

    // ---------- Host lifecycle — Життєвий цикл хоста ----------

    /**
     * Sector host state after its last heartbeat (sent every 10 s).
     * — Стан хоста сектора після останнього heartbeat (іде кожні 10 с).
     *   0-15 s  OK    alive, others join as clients — живий, інші заходять клієнтами
     *  15-30 s  LOST  silent, sector locked while we check — мовчить, сектор закритий на перевірку
     *  30-45 s  ADAPT grace time, only the same host may return — запас часу, повернутись може лише сам хост
     *  45+  s   FREE  sector open, first comer hosts from last save — відкрито, перший стане хостом з останнього сейву
     */
    static final long OK_TIMEOUT = 15_000L, LOST_TIMEOUT = 30_000L, ADAPT_TIMEOUT = 45_000L;
    /** Claimed but never sent a heartbeat (still loading the world) — Заклеймив, але ще без heartbeat (вантажить світ). */
    static final long PENDING_TIMEOUT = 12_000L;
    static final int OK = 0, LOST = 1, ADAPT = 2, FREE = 3;
    static final String[] STATE_NAMES = {"ok", "lost", "adapt", "free"};

    static class Host {
        final String ip, player;
        final int port;
        final long lastSeen;
        /** True after the first heartbeat: the host is already listening — true після першого heartbeat: хост уже слухає порт. */
        final boolean ready;
        /** Display name and the real address the claim came from (for bans) — Ім'я і справжня адреса, з якої прийшов claim (для банів). */
        String name = "", addr = "";
        /** Secret given to this host on claim; every host call must carry it — Секрет, виданий цьому хосту при клеймі; кожен виклик хоста має його нести. */
        String key = "";

        Host(String ip, int port, String player, long lastSeen, boolean ready) {
            this.ip = ip;
            this.port = port;
            this.player = player;
            this.lastSeen = lastSeen;
            this.ready = ready;
        }

        Host with(String name, String addr) {
            this.name = name == null ? "" : name;
            this.addr = addr == null ? "" : addr;
            return this;
        }

        Host keyed(String key) {
            this.key = key;
            return this;
        }

        /** Still loading the sector: alive, but not listening yet — Ще вантажить сектор: живий, але ще не слухає. */
        Host touch(long now) {
            return new Host(ip, port, player, now, ready).with(name, addr).keyed(key);
        }

        int stateAt(long now) {
            long age = now - lastSeen;
            if (!ready) return age < PENDING_TIMEOUT ? OK : FREE;
            return age < OK_TIMEOUT ? OK : age < LOST_TIMEOUT ? LOST : age < ADAPT_TIMEOUT ? ADAPT : FREE;
        }

        /** Seconds until the sector opens — Скільки секунд до відкриття сектора. */
        int secondsToFree(long now) {
            return (int) Math.max(1, ((ready ? ADAPT_TIMEOUT : PENDING_TIMEOUT) - (now - lastSeen) + 999) / 1000);
        }

        boolean alive(long now) {
            return stateAt(now) != FREE;
        }

        Host beat(long now) {
            return new Host(ip, port, player, now, true).with(name, addr).keyed(key);
        }

        /** Player UUID is never sent: it is the player's "key" to the sector — UUID гравця не віддаємо: це його "ключ" до сектора. */
        String json(long now) {
            return "{\"ip\":" + q(ip) + ",\"port\":" + port + ",\"lastSeen\":" + lastSeen + ",\"ready\":" + ready
                + ",\"state\":" + q(STATE_NAMES[stateAt(now)]) + "}";
        }
    }

    static Server server;
    static int boundPort = -1;
    /** Request handlers run here, never on ArcNet's update thread — Обробники запитів працюють тут, ніколи не в потоці оновлення ArcNet. */
    static ThreadPoolExecutor pool;
    static ScheduledExecutorService sweeper;
    static final ConcurrentHashMap<String, Host> hosts = new ConcurrentHashMap<>();
    /**
     * Sectors that have a save on disk. Saves are NOT kept in memory: read from disk when someone downloads one,
     * so junk uploads can't fill the game's memory (and crash it again on every start).
     * — Сектори, що мають сейв на диску. Сейви НЕ тримаються в пам'яті: читаються з диска, коли хтось його завантажує,
     * тож сміттєві заливання не заб'ють пам'ять гри (і не валитимуть її знову при кожному запуску).
     */
    static final Set<String> saves = ConcurrentHashMap.newKeySet();
    /** All sector saves together may not take more disk than this — Усі сейви секторів разом не можуть займати на диску більше. */
    static final long SAVES_QUOTA = 1024L * 1024 * 1024;
    static final ConcurrentHashMap<String, String> infos = new ConcurrentHashMap<>();
    /** Sector state bits from its info, cached (1 core, 2 waves, 4 attack) — Біти стану сектора з info, кешовані (1 ядро, 2 хвилі, 4 атака). */
    static final ConcurrentHashMap<String, Integer> infoBits = new ConcurrentHashMap<>();
    static final Set<String> unlocked = ConcurrentHashMap.newKeySet();
    /** Who researched what (memory only, for toasts) — Хто що дослідив (лише в пам'яті, для сповіщень). */
    static final ConcurrentHashMap<String, String> unlockedBy = new ConcurrentHashMap<>();

    public static boolean running() {
        return server != null;
    }

    public static int port() {
        return server == null ? -1 : boundPort;
    }

    // ---------- Who may connect: this PC, LAN, Tailscale; never the internet — Хто може підключитись: цей ПК, LAN, Tailscale; інтернет — ніколи ----------

    /** Tailscale: 100.64.0.0/10 and fd7a:115c:a1e0::/48 — Tailscale: 100.64.0.0/10 і fd7a:115c:a1e0::/48. */
    static boolean isTailscale(InetAddress a) {
        byte[] b = a.getAddress();
        // 100.64.0.0/10 is also the ISP carrier-grade NAT range: trust it only if this PC really is on Tailscale
        // — 100.64.0.0/10 — це ще й діапазон CGNAT провайдерів: довіряємо, лише якщо цей ПК справді в Tailscale
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64 && tailscaleHere();
        return b.length == 16 && (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0x5c
            && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
    }

    static volatile boolean tailscaleCached;
    static volatile long tailscaleCheckedAt;

    /** This PC has a Tailscale (100.64.0.0/10) address; rechecked every 30 s — Цей ПК має адресу Tailscale (100.64.0.0/10); перевірка раз на 30 с. */
    static boolean tailscaleHere() {
        long now = System.currentTimeMillis();
        if (now - tailscaleCheckedAt > 30_000L) {
            boolean found = false;
            for (String ip : ownIps()) {
                String[] p = ip.split("\\.");
                if (p.length == 4 && p[0].equals("100")) {
                    int second = Strings.parseInt(p[1], -1);
                    if (second >= 64 && second <= 127) found = true;
                }
            }
            tailscaleCached = found;
            tailscaleCheckedAt = now;
        }
        return tailscaleCached;
    }

    /** Private networks: 10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA — Приватні мережі: 10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA. */
    static boolean isLan(InetAddress a) {
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /** Allow a request from this address? Public internet never — Чи пускати запит з цієї адреси? Інтернет — ніколи. */
    static boolean allowed(InetAddress a) {
        if (a.isLoopbackAddress()) return true;
        // IPv4 wrapped in IPv6 (::ffff:x.x.x.x) — IPv4, загорнута в IPv6
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            for (int i = 0; i < 10 && mapped; i++) if (b[i] != 0) mapped = false;
            if (mapped) {
                try {
                    a = InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
                } catch (Exception ignored) {
                }
            }
        }
        return isLan(a) || isTailscale(a);
    }

    // ---------- Stats for the host dialog — Статистика для вікна хоста ----------

    public static int liveCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Host h : hosts.values()) if (h.alive(now)) n++;
        return n;
    }

    /** "sector - ip:port" for every live host — "сектор - ip:port" для кожного живого хоста. */
    public static List<String> liveList() {
        long now = System.currentTimeMillis();
        List<String> out = new ArrayList<>();
        hosts.forEach((id, h) -> {
            if (h.alive(now)) out.add(id + "  -  " + h.ip + ":" + h.port);
        });
        Collections.sort(out);
        return out;
    }

    public static int savedCount() {
        return saves.size();
    }

    public static int unlockCount() {
        return unlocked.size();
    }

    // ---------- Start / stop — Запуск / зупинка ----------

    static Fi dir() {
        return Core.settings.getDataDirectory().child("aurora-authority");
    }

    /** Starts the server. True if it is running (already or just now) — Запускає сервер. true, якщо працює (уже або щойно). */
    public static synchronized boolean start() {
        if (server != null) return true;
        try {
            int port = Core.settings.getInt("aurora-port", 8443);
            load();
            ownerUuid = Vars.platform.getUUID();

            Server s = new Server(AuroraWire.WRITE_BUF, AuroraWire.OBJ_BUF, new AuroraWire.Serializer());
            // Scope check already at connect time; handle() checks it again per request — Перевірка доступу вже при підключенні; handle() перевіряє ще раз на кожен запит
            s.setConnectFilter(addr -> {
                try {
                    return allowed(InetAddress.getByName(addr));
                } catch (Exception e) {
                    return false;
                }
            });
            // 16 threads that really start (with a queue, a pool only grows past core size when the queue is full);
            // idle ones stop after 30 s — 16 потоків, які справді запускаються (з чергою пул росте понад core лише коли черга повна);
            // бездіяльні зупиняються через 30 с
            ThreadPoolExecutor exec = new ThreadPoolExecutor(16, 16, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(512), r -> {
                Thread t = new Thread(r, "aurora-authority");
                t.setDaemon(true);
                return t;
            });
            exec.allowCoreThreadTimeOut(true);
            // Answer LAN discovery ("Local servers") while we are on our planet and no sector server of ours is running
            // (then the game server answers itself). Multicast only: the UDP game port belongs to the game server.
            // — Відповідаємо на пошук у LAN ("Локальні сервери"), поки ми на своїй планеті й наш сервер сектора не запущений
            // (тоді відповідає сам сервер гри). Лише multicast: UDP-порт гри належить серверу гри.
            s.setMulticast(Vars.multicastGroup, Vars.multicastPort);
            s.setDiscoveryHandler((address, handler) -> {
                if (Vars.net.server() || Vars.campaignSlot != Vars.slotAurora || !allowed(address)) return;
                java.nio.ByteBuffer buf = mindustry.net.NetworkIO.writeServerData(
                    Vars.player == null ? "Aurora" : Vars.player.name, "Aurora", liveCount(), 0,
                    mindustry.game.Gamemode.survival.ordinal(), 0, AuroraNet.tr("discovery.desc"), "Aurora", Core.settings.getInt("port", Vars.port));
                handler.respond(buf);
            });
            s.addListener(new NetListener() {
                @Override
                public void connected(Connection c) {
                    // Few links per address: a game needs one — Мало з'єднань з однієї адреси: грі потрібне одне
                    String ip = ipOf(c);
                    if (ip.isEmpty()) { // already gone — вже відключився
                        c.close(DcReason.closed);
                        return;
                    }
                    connIp.put(c, ip);
                    int n = linksPerIp.computeIfAbsent(ip, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
                    // One unfinished request at a time per link (a game sends them one by one per link), and a global cap on
                    // bytes being received, so nobody can fill memory before the password is even checked
                    // — Одна незавершена заявка на з'єднання (гра шле їх по одній), і загальна межа байтів, що приймаються,
                    // щоб ніхто не забив пам'ять ще до перевірки пароля
                    c.setArbitraryData(new AuroraWire.Assembler(MAX_BODY + 4096, 2, assembling, MAX_ASSEMBLING));
                    // No exemption for 127.0.0.1: a tunnel can make strangers come from there too (our own game needs 1 link)
                    // — Без винятку для 127.0.0.1: через тунель звідти можуть приходити й чужі (нашій грі потрібне 1 з'єднання)
                    if (n > MAX_LINKS_PER_IP || totalLinks() > MAX_LINKS) {
                        Log.warn("Aurora Authority: too many links from @, closing", ip);
                        c.close(DcReason.closed);
                    }
                }

                @Override
                public void disconnected(Connection c, DcReason reason) {
                    // The address is gone after disconnect, use the one saved at connect — Після відключення адреси вже нема, беремо збережену при підключенні
                    if (c.getArbitraryData() instanceof AuroraWire.Assembler a) a.clear();
                    String ip = connIp.remove(c);
                    if (ip == null) return;
                    var n = linksPerIp.get(ip);
                    if (n != null && n.decrementAndGet() <= 0) linksPerIp.remove(ip, n);
                }

                @Override
                public void received(Connection c, Object o) {
                    if (!(o instanceof AuroraWire.Frame f) || f.kind != AuroraWire.REQ) return;
                    byte[] full;
                    try {
                        full = ((AuroraWire.Assembler) c.getArbitraryData()).add(f);
                    } catch (IOException e) {
                        Log.warn("Aurora Authority: dropping @ (@)", c, e.getMessage());
                        c.close(DcReason.error);
                        return;
                    }
                    if (full == null) return;
                    // The address saved at connect: after a disconnect ArcNet no longer knows it — Адреса, збережена при підключенні: після відключення ArcNet її вже не знає
                    String from = connIp.get(c);
                    if (from == null || from.isEmpty()) {
                        c.close(DcReason.error);
                        return;
                    }
                    // Too many requests from this address: drop the link. Never answer from here: a send can wait for a client
                    // that doesn't read, and this thread serves every connection. The game reconnects on its next request.
                    // — Забагато запитів з цієї адреси: рвемо з'єднання. Ніколи не відповідаємо звідси: відправка може чекати на
                    // клієнта, який не читає, а цей потік обслуговує всі з'єднання. Гра перепідключиться на наступному запиті.
                    if (!allowRate(from)) {
                        Log.warn("Aurora Authority: too many requests from @, closing the link", from);
                        c.close(DcReason.closed);
                        return;
                    }
                    try {
                        exec.execute(() -> serve(c, f.id, full, from));
                    } catch (RejectedExecutionException e) {
                        c.close(DcReason.error); // overloaded — перевантажено
                    }
                }
            });
            s.bind(new InetSocketAddress(Core.settings.getString("aurora-bind", "0.0.0.0"), port), null);
            Thread loop = new Thread(s, "aurora-authority-net");
            loop.setDaemon(true);
            loop.start();
            ScheduledExecutorService sw = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "aurora-authority-waits");
                t.setDaemon(true);
                return t;
            });
            sw.scheduleAtFixedRate(AuroraAuthority::expireWaiters, 1, 1, TimeUnit.SECONDS);
            // Offline production, like vanilla: every PRODUCE_SECONDS, on this sweeper thread — Виробництво без гравців, як у ванілі: кожні PRODUCE_SECONDS, у цьому потоці прибиральника
            sw.scheduleAtFixedRate(() -> {
                try {
                    produce(PRODUCE_SECONDS);
                } catch (Throwable e) {
                    Log.err("Aurora production failed", e);
                }
            }, (long) PRODUCE_SECONDS, (long) PRODUCE_SECONDS, TimeUnit.SECONDS);
            pool = exec;
            sweeper = sw;
            boundPort = port;
            server = s;
            // The friend's Authority URL is not touched: AuroraNet.authority() uses 127.0.0.1 on our own planet
            // — Адресу Authority друга не чіпаємо: AuroraNet.authority() на своїй планеті сам бере 127.0.0.1
            AuroraNet.localAuthority = true;
            return true;
        } catch (BindException e) {
            return false;
        } catch (Throwable e) {
            Log.err("Aurora Authority failed to start", e);
            return false;
        }
    }

    public static synchronized void stop() {
        if (server == null) return;
        try {
            flushProduction();
        } catch (Throwable e) {
            Log.err("Aurora production flush failed", e);
        }
        try {
            server.stop();
            server.dispose();
        } catch (Throwable ignored) {
        }
        server = null;
        boundPort = -1;
        if (pool != null) pool.shutdownNow();
        if (sweeper != null) sweeper.shutdownNow();
        pool = null;
        sweeper = null;
        for (List<Waiter> l : waiters.values()) for (Waiter w : l) w.ex.finish(503, new byte[0]);
        waiters.clear();
        AuroraNet.localAuthority = false;
        hosts.clear();
        linksPerIp.clear();
        connIp.clear();
        buckets.clear();
    }

    // ---------- Disk — Диск ----------

    static void load() {
        try {
            Fi d = dir();
            if (!d.exists()) return;
            for (Fi f : d.list()) {
                String n = f.name();
                // Leftover of a write interrupted by a crash — Залишок запису, перерваного падінням
                if (n.endsWith(".tmp")) {
                    f.delete();
                    continue;
                }
                if (n.endsWith(".msav")) saves.add(n.substring(0, n.length() - 5)); // only the id, the file stays on disk — лише id, файл лишається на диску
                else if (n.endsWith(".info")) putInfo(n.substring(0, n.length() - 5), f.readString());
            }
            Fi u = d.child("unlocked.txt");
            if (u.exists()) {
                for (String line : u.readString().split("\n")) {
                    if (!line.trim().isEmpty()) unlocked.add(line.trim());
                }
            }
            loadEconomy();
            loadBans();
        } catch (Throwable e) {
            Log.err("Aurora Authority load failed", e);
        }
    }

    /**
     * Stores a sector info and caches its state bits. SectorInfo JSON skips default values: hasCore=true, waves=true, attack=false.
     * — Зберігає info сектора й кешує біти стану. JSON SectorInfo пропускає значення за замовчуванням: hasCore=true, waves=true, attack=false.
     */
    static void putInfo(String id, String info) {
        infos.put(id, info);
        int bits = 1 | 2;
        try {
            JsonValue j = new JsonReader().parse(info);
            bits = (j.getBoolean("hasCore", true) ? 1 : 0) | (j.getBoolean("waves", true) ? 2 : 0) | (j.getBoolean("attack", false) ? 4 : 0);
        } catch (Throwable ignored) {
        }
        infoBits.put(id, bits);
    }

    static synchronized void saveUnlocks() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : unlocked) sb.append(s).append('\n');
            writeAtomic(dir().child("unlocked.txt"), sb.toString());
        } catch (Throwable e) {
            Log.err(e);
        }
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

    static synchronized void saveEconomy() {
        try {
            StringBuilder sb = new StringBuilder("{\"stock\":{");
            boolean first = true;
            for (Map.Entry<String, Map<String, Integer>> e : stock.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(q(e.getKey())).append(':').append(countsJson(e.getValue()));
            }
            sb.append("},\"pending\":{");
            first = true;
            for (Map.Entry<String, CopyOnWriteArrayList<Op>> e : pending.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(q(e.getKey())).append(':').append(opsJson(e.getValue()));
            }
            sb.append("}}");
            // Temp file first, then replace: a crash never leaves a cut JSON — Спершу тимчасовий файл, потім заміна: краш не лишить обрізаний JSON
            writeAtomic(dir().child("economy.json"), sb.toString());
        } catch (Throwable e) {
            Log.err("Aurora economy save failed", e);
        }
    }

    // ---------- Requests — Запити ----------

    static void handle(Exchange ex) {
        try {
            // Who may knock: this PC, LAN, Tailscale — Хто може стукати: цей ПК, LAN, Tailscale
            if (!allowed(ex.getRemoteAddress().getAddress())) {
                send(ex, 403, "");
                return;
            }

            // Banned on this planet: a clear answer (not a dropped connection), so his game stops at once
            // — Заблокований на цій планеті: чітка відповідь (а не обірване з'єднання), щоб його гра зупинилась одразу
            if (!fromThisPc(ex) && bannedIps.contains(plainAddr(ex.getRemoteAddress().getAddress()))) {
                json(ex, 403, "{\"banned\":true}");
                return;
            }

            String token = AuroraNet.hostToken(); // our planet's password, not the one for friends' planets — пароль нашої планети, а не той, що для планет друзів
            if (!token.isEmpty() && !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), ex.password.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, "");
                return;
            }

            URI uri = ex.getRequestURI();
            String m = ex.req.method;
            String[] p = uri.getPath().split("/");
            boolean get = m.equals("GET");

            // /version is open; everything else needs the same protocol — /version відкритий; решта — лише з тією самою версією протоколу
            if (get && p.length == 2 && p[1].equals("version")) {
                json(ex, 200, "{\"proto\":" + AuroraNet.PROTOCOL + "}");
                return;
            }
            if (ex.req.proto != AuroraNet.PROTOCOL) {
                json(ex, 426, "{\"proto\":" + AuroraNet.PROTOCOL + "}");
                return;
            }

            String path = p.length >= 2 ? p[1] : "";

            if (ex.req.body.length > bodyLimit(uri.getPath())) {
                send(ex, 413, ""); // too large for this request — завелике для цього запиту
                return;
            }

            if (get && p.length == 2 && path.equals("unlocks")) {
                // {"name": "who researched", ...}, "" if unknown — {"назва": "хто дослідив", ...}, "" якщо невідомо
                StringBuilder sb = new StringBuilder("{");
                for (String s : unlocked) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s)).append(':').append(q(unlockedBy.getOrDefault(s, "")));
                }
                json(ex, 200, sb.append('}').toString());

            } else if (get && p.length == 3 && path.equals("unlock")) {
                if (!isContent(p[2])) {
                    send(ex, 400, "");
                    return;
                }
                String by = query(uri, "by");
                if (by.length() > 40) by = by.substring(0, 40);
                // Research with a cost opens only through a paid /spend (or from this PC) — Дослідження з ціною відкривається лише через оплачений /spend (або з цього ПК)
                if (!unlocked.contains(p[2]) && researchCost(p[2]) != null && !fromThisPc(ex)) {
                    send(ex, 403, "");
                    return;
                }
                if (unlocked.add(p[2])) {
                    if (!by.isEmpty()) unlockedBy.put(p[2], by);
                    saveUnlocks();
                }
                json(ex, 200, "{\"name\":" + q(p[2]) + ",\"unlocked\":true}");

            } else if (get && p.length == 2 && path.equals("sectors")) {
                long now = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder("{");
                hosts.forEach((id, h) -> {
                    if (!h.alive(now)) return;
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(id)).append(':').append(h.json(now));
                });
                json(ex, 200, sb.append('}').toString());

            } else if (get && p.length == 2 && path.equals("owner")) {
                // Who the planet host is: a hash of the uuid (never the uuid itself) + own IPs, so sector hosts can give him admin
                // — Хто хост планети: хеш uuid (ніколи сам uuid) + власні IP, щоб хости секторів давали йому адмінку
                StringBuilder sb = new StringBuilder("{\"uuidHash\":").append(q(sha256(ownerUuid))).append(",\"ips\":[");
                boolean first = true;
                for (String ip : ownIps()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(q(ip));
                }
                json(ex, 200, sb.append("]}").toString());

            } else if (get && p.length == 2 && path.equals("bans")) {
                StringBuilder sb = new StringBuilder("{\"uuids\":[");
                boolean first = true;
                for (String u : bans.keySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(q(u));
                }
                sb.append("],\"ips\":[");
                first = true;
                for (String ip : bannedIps) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append(q(ip));
                }
                json(ex, 200, sb.append("]}").toString());

            } else if (get && p.length == 2 && path.equals("rules")) {
                // Planet campaign rules set by the planet host: {"serpulo":{...}, ...} — Правила кампанії планети, які задає хост планети
                json(ex, 200, rulesJson());

            } else if (get && p.length == 2 && path.equals("mode")) {
                // Is the planet host in Aurora right now (not normal campaign / own game) — Чи хост планети зараз в Aurora (а не у звичайній кампанії / своїй грі)
                json(ex, 200, "{\"active\":" + (Vars.campaignSlot == Vars.slotAurora) + "}");

            } else if (get && p.length == 3 && path.equals("sectors") && p[2].equals("state")) {
                // {"id": bits}: 1 core, 2 waves still running, 4 attacking an enemy base — 1 ядро, 2 хвилі ще йдуть, 4 атака на ворожу базу
                StringBuilder sb = new StringBuilder("{");
                for (String s : saves) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s)).append(':').append(infoBits.getOrDefault(s, 1 | 2));
                }
                json(ex, 200, sb.append('}').toString());

            } else if (p.length == 4 && path.equals("sector") && validSector(p[2])) {
                sector(ex, m, p[2], p[3], query(uri, "player"), query(uri, "key"));

            } else if (get && p.length == 2 && path.equals("items")) {
                // ?fresh=1: ask the hosts for their current stock first — ?fresh=1: спершу попросити в хостів поточний склад
                if (query(uri, "fresh").equals("1")) freshStock();
                // Shared stock = sum of all sectors minus pending ops — Спільний склад = сума всіх секторів мінус ще не застосовані списання
                Map<String, Integer> sum = new TreeMap<>();
                stock.forEach((sec, items) -> {
                    for (String item : items.keySet()) sum.merge(item, shown(sec, item), Integer::sum);
                });
                sum.values().removeIf(v -> v <= 0);
                json(ex, 200, countsJson(sum));

            } else if (m.equals("POST") && p.length == 2 && path.equals("spend")) {
                spend(ex);

            } else {
                send(ex, 404, "");
            }
        } catch (Throwable e) {
            Log.err("Aurora Authority request failed", e);
            try {
                send(ex, 400, "");
            } catch (Throwable ignored) {
            }
        } finally {
            ex.close();
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
    static synchronized void produce(float secs) {
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

    /** Writes produced sector infos and the stock to disk — Пише вироблене (info секторів і склад) на диск. */
    static synchronized void flushProduction() {
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

    // ---------- Planet bans — Блокування на планеті ----------

    /** uuid -> {ip, name} */
    static final Map<String, String[]> bans = new ConcurrentHashMap<>();
    static final Set<String> bannedIps = ConcurrentHashMap.newKeySet();
    /** Planet host's player uuid (never banned) — uuid гравця-хоста планети (його не можна заблокувати). */
    static volatile String ownerUuid = "";

    static void loadBans() {
        Fi f = dir().child("bans.txt");
        if (!f.exists()) return;
        for (String line : f.readString().split("\n")) {
            String[] c = line.split("\t", -1);
            if (c.length < 3 || c[0].isEmpty()) continue;
            bans.put(c[0], new String[]{c[1], c[2]});
            if (!c[1].isEmpty()) bannedIps.add(c[1]);
        }
    }

    static synchronized void saveBans() {
        try {
            StringBuilder sb = new StringBuilder();
            bans.forEach((u, v) -> sb.append(u).append('\t').append(v[0]).append('\t').append(v[1].replace('\t', ' ').replace('\n', ' ')).append('\n'));
            writeAtomic(dir().child("bans.txt"), sb.toString());
        } catch (Throwable e) {
            Log.err("Aurora bans save failed", e);
        }
    }

    /** IPv4 text even for IPv4-mapped IPv6 — Текст IPv4 навіть для IPv4, загорнутої в IPv6. */
    static String plainAddr(InetAddress a) {
        String s = a.getHostAddress();
        return s.startsWith("::ffff:") ? s.substring(7) : s;
    }

    /**
     * Planet host abandons a sector nobody plays: its save, info and stock are gone, the sector is free again.
     * False if someone plays it now. — Власник планети покидає сектор, де ніхто не грає: сейв, info і склад видаляються, сектор знову вільний.
     * false, якщо там зараз хтось грає.
     */
    public static boolean abandon(String id) {
        Host h = hosts.get(id);
        if (h != null && h.alive(System.currentTimeMillis())) return false;
        hosts.remove(id);
        saves.remove(id);
        infos.remove(id);
        infoBits.remove(id);
        stock.remove(id);
        pending.remove(id);
        sectorPlayers.remove(id);
        try {
            dir().child(id + ".msav").delete();
            dir().child(id + ".info").delete();
        } catch (Throwable e) {
            Log.err("Aurora abandon: file delete failed", e);
        }
        saveEconomy();
        Log.info("Aurora: sector @ abandoned by the planet host", id);
        return true;
    }

    /** Frees his sectors right away (no waiting for timeouts) — Звільняє його сектори одразу (без очікування таймаутів). */
    static void dropHost(String uuid, String ip) {
        hosts.forEach((id, h) -> {
            if (h.player.equals(uuid) || (ip != null && !ip.isEmpty() && ip.equals(h.addr))) {
                if (hosts.remove(id, h)) {
                    sectorPlayers.remove(id);
                    Log.info("Aurora: sector @ freed (host removed by the planet host)", id);
                }
            }
        });
    }

    /** Bans a player on the whole planet: uuid + IP — Блокує гравця на всій планеті: uuid + IP. */
    public static void ban(String uuid, String ip, String name, String why) {
        if (uuid == null || uuid.isEmpty() || uuid.equals(ownerUuid)) return;
        if (ip == null) ip = "";
        // Never this PC's own addresses: that would lock the planet host out of his own planet (the uuid is still banned)
        // — Ніколи адреси цього ПК: це закрило б власника планети від його ж планети (uuid однаково заблоковано)
        if (isLoopback(ip) || ownIps().contains(ip)) ip = "";
        bans.put(uuid, new String[]{ip, name == null ? "" : name});
        if (!ip.isEmpty()) bannedIps.add(ip);
        saveBans();
        dropHost(uuid, ip);
        Log.info("Aurora: banned @ (@) @ by @", name, uuid, ip, why);
    }

    public static void unban(String uuid) {
        String[] v = bans.remove(uuid);
        if (v == null) return;
        bannedIps.clear();
        bans.values().forEach(b -> {
            if (!b[0].isEmpty()) bannedIps.add(b[0]);
        });
        saveBans();
        Log.info("Aurora: unbanned @ (@)", v[1], uuid);
    }

    /** Players of each live sector, reported by its host: sector -> [{uuid, ip, name}] — Гравці кожного живого сектора від його хоста. */
    static final ConcurrentHashMap<String, List<String[]>> sectorPlayers = new ConcurrentHashMap<>();
    /** Kicked by the planet host: uuid -> may come back at (ms) — Видалені хостом планети: uuid -> коли можна повернутись (мс). */
    static final ConcurrentHashMap<String, Long> kickedUntil = new ConcurrentHashMap<>();
    static final long KICK_MS = 60_000L;

    static String cut(String s) {
        return s == null ? "" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** Banned or kicked less than a minute ago — Заблокований або видалений менше хвилини тому. */
    static boolean removed(String uuid) {
        if (uuid == null || uuid.isEmpty() || uuid.equals(ownerUuid)) return false;
        if (bans.containsKey(uuid)) return true;
        Long t = kickedUntil.get(uuid);
        return t != null && t > System.currentTimeMillis();
    }

    /** {"banned":true} or {"kicked":seconds left} — {"banned":true} або {"kicked":скільки секунд лишилось}. */
    static String removedJson(String uuid) {
        if (bans.containsKey(uuid)) return "{\"banned\":true}";
        Long t = kickedUntil.get(uuid);
        long left = t == null ? 0 : Math.max(1, (t - System.currentTimeMillis() + 999) / 1000);
        return "{\"kicked\":" + left + "}";
    }

    /** Kick from the planet: out of his sector (hosts too), no re-entry for a minute — Видалити з планети: геть із сектора (хостів теж), без повернення хвилину. */
    public static void kick(String uuid) {
        if (uuid == null || uuid.isEmpty() || uuid.equals(ownerUuid)) return;
        kickedUntil.put(uuid, System.currentTimeMillis() + KICK_MS);
        dropHost(uuid, null);
        Log.info("Aurora: kicked @ from the planet", shortId(uuid));
    }

    /**
     * Planet host, as a client in someone's sector, removes a player he sees there. Only the planet host's own game calls this
     * (in-process, never over the network), so nobody else can ban. The player is found by his entity id in that sector,
     * from the sector host's last heartbeat. Returns his name, or null if Authority does not know him yet (joined < 10 s ago).
     * — Власник планети, будучи клієнтом у чужому секторі, видаляє гравця, якого там бачить. Викликає лише гра власника
     * (в тому ж процесі, ніколи через мережу), тож ніхто інший банити не може. Гравця шукаємо за id сутності в тому секторі,
     * з останнього heartbeat хоста. Повертає ім'я, або null, якщо Authority його ще не знає (зайшов < 10 с тому).
     */
    public static String removeInSector(String sector, int pid, boolean ban) {
        for (String[] c : sectorPlayers.getOrDefault(sector, Collections.emptyList())) {
            if (c.length < 4 || !c[3].equals(String.valueOf(pid))) continue;
            if (c[0].equals(ownerUuid)) return null;
            String ip = c[1];
            Host h = hosts.get(sector);
            if (ip.isEmpty() && h != null && h.player.equals(c[0])) ip = h.addr; // the sector host himself — сам хост сектора
            if (ban) ban(c[0], ip, c[2], "planet host (in sector)");
            else kick(c[0]);
            return c[2].isEmpty() ? shortId(c[0]) : c[2];
        }
        return null;
    }

    /** One player on the planet for the owner's list — Один гравець на планеті для списку власника. */
    public static class PlanetPlayer {
        public String sector, uuid, name, ip;
        public boolean host;
    }

    /** Everyone in live sectors: hosts and the players they report — Усі в живих секторах: хости і гравці, яких вони повідомили. */
    public static List<PlanetPlayer> players() {
        long now = System.currentTimeMillis();
        List<PlanetPlayer> out = new ArrayList<>();
        hosts.forEach((id, h) -> {
            if (!h.alive(now)) return;
            PlanetPlayer p = new PlanetPlayer();
            p.sector = id;
            p.uuid = h.player;
            p.name = h.name.isEmpty() ? shortId(h.player) : h.name;
            p.ip = h.addr.isEmpty() ? h.ip : h.addr;
            p.host = true;
            out.add(p);
            for (String[] c : sectorPlayers.getOrDefault(id, Collections.emptyList())) {
                if (c[0].equals(h.player)) continue;
                PlanetPlayer q = new PlanetPlayer();
                q.sector = id;
                q.uuid = c[0];
                q.name = c[2].isEmpty() ? shortId(c[0]) : c[2];
                q.ip = c[1];
                out.add(q);
            }
        });
        out.sort(Comparator.comparing((PlanetPlayer p) -> p.sector).thenComparing(p -> !p.host).thenComparing(p -> p.name));
        return out;
    }

    public static boolean isOwner(String uuid) {
        return uuid != null && uuid.equals(ownerUuid);
    }

    /** For the host dialog: uuid -> {ip, name} — Для вікна хоста: uuid -> {ip, ім'я}. */
    public static Map<String, String[]> banList() {
        return new TreeMap<>(bans);
    }

    /** IPv4 addresses of this PC (LAN, Tailscale) — IPv4-адреси цього ПК (LAN, Tailscale). */
    static List<String> ownIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Own Json: JsonIO.json is shared with the main thread and not thread-safe — Свій Json: JsonIO.json спільний з головним потоком і не потокобезпечний. */
    static final arc.util.serialization.Json rulesJsonWriter = new arc.util.serialization.Json(arc.util.serialization.JsonWriter.OutputType.json);

    /** Campaign rules of every planet from the host's own Aurora rules (c2- keys), difficulty clamped to Eradication+
     * — Правила кампанії кожної планети з власних правил Aurora хоста (ключі c2-), складність не нижче Викорінювальної. */
    static synchronized String rulesJson() {
        StringBuilder sb = new StringBuilder("{");
        for (mindustry.type.Planet pl : Vars.content.planets()) {
            if (pl.sectors.isEmpty() || !pl.allowCampaignRules) continue;
            // Raw bytes + our own Json: Core.settings.getJson would use JsonIO.json, which belongs to the main thread
            // — Сирі байти + власний Json: Core.settings.getJson використав би JsonIO.json, який належить головному потоку
            mindustry.game.CampaignRules r = null;
            try {
                byte[] raw = Core.settings.getBytes("c2-" + pl.name + "-campaign-rules");
                if (raw != null) r = rulesJsonWriter.readValue(mindustry.game.CampaignRules.class, new UBJsonReader().parse(new ByteArrayInputStream(raw)));
            } catch (Throwable ignored) {
            }
            if (r == null) {
                // The planet's defaults, copied through our own Json — Типові правила планети, скопійовані через власний Json
                r = rulesJsonWriter.fromJson(mindustry.game.CampaignRules.class, rulesJsonWriter.toJson(pl.campaignRuleDefaults, mindustry.game.CampaignRules.class));
                r.sectorInvasion = pl.allowSectorInvasion;
            }
            if (r.difficulty == null || r.difficulty.ordinal() < mindustry.game.Difficulty.eradication.ordinal()) r.difficulty = mindustry.game.Difficulty.eradication;
            if (sb.length() > 1) sb.append(',');
            sb.append(q(pl.name)).append(':').append(rulesJsonWriter.toJson(r, mindustry.game.CampaignRules.class));
        }
        return sb.append('}').toString();
    }

    /** /sector/{id}/{action} */
    static void sector(Exchange ex, String m, String id, String action, String player, String key) throws Exception {
        boolean get = m.equals("GET"), post = m.equals("POST");

        if (post && action.equals("claim")) {
            JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            String who = j.getString("player");
            // The planet host's uuid only from the planet host's own PC: someone who learned it can't hide behind it
            // — uuid власника планети лише з його власного ПК: той, хто його дізнався, не сховається за ним
            if (who.equals(ownerUuid) && !fromThisPc(ex)) {
                json(ex, 403, "{}");
                return;
            }
            if (removed(who)) {
                // Banned, or kicked less than a minute ago: say which — Заблокований або видалений менше хвилини тому: кажемо, що саме
                json(ex, 403, removedJson(who));
                return;
            }
            String nm = j.getString("name", "");
            if (nm.length() > 40) nm = nm.substring(0, 40);
            String theirKey = j.getString("key", "");
            String fromAddr = ex.getRemoteAddress().getAddress().getHostAddress();
            // A game hosts one sector at a time: claiming another frees the previous one, and one address may hold
            // at most 2 live sectors. So nobody can grab many sectors at once to fill them with junk.
            // — Гра хостить один сектор за раз: клейм іншого звільняє попередній, а одна адреса може тримати
            // щонайбільше 2 живі сектори. Тож ніхто не захопить багато секторів, щоб заповнити їх сміттям.
            hosts.forEach((oid, h) -> {
                // same uuid AND same address: a copied uuid from elsewhere can't free someone's sector
                // — той самий uuid І та сама адреса: скопійований uuid з іншого місця не звільнить чужий сектор
                if (!oid.equals(id) && h.player.equals(who) && h.addr.equals(fromAddr) && hosts.remove(oid, h)) sectorPlayers.remove(oid);
            });
            if (!fromThisPc(ex)) {
                int sameAddr = 0;
                for (Map.Entry<String, Host> e : hosts.entrySet()) {
                    Host h = e.getValue();
                    if (!e.getKey().equals(id) && h.alive(now) && h.addr.equals(fromAddr)) sameAddr++;
                }
                if (sameAddr >= 2) {
                    json(ex, 429, "{}");
                    return;
                }
            }
            // The address friends will connect to: the one the host reports (e.g. its Tailscale IP), but only a LAN / Tailscale
            // literal; anything else (a public server, a name) becomes the address the claim actually came from
            // — Адреса, до якої підключатимуться друзі: та, яку повідомив хост (напр. його Tailscale-IP), але лише літерал
            // LAN / Tailscale; будь-що інше (публічний сервер, ім'я) замінюється адресою, з якої реально прийшов клейм
            String reported = j.getString("ip", "");
            String joinIp = privateLiteral(reported) ? reported : fromAddr;
            int joinPort = j.getInt("port", Vars.port);
            if (joinPort <= 0 || joinPort > 65535) joinPort = Vars.port;
            Host mine = new Host(joinIp, joinPort, who, now, false).with(nm, fromAddr).keyed(newKey());
            Host prev = hosts.get(id);
            // A live host stays unless the claim comes with its key (the same game re-claiming); a dead one is replaced.
            // Same uuid without the key (a copied uuid, or our own game restarted) waits until the old entry expires.
            // — Живий хост лишається, якщо клейм не несе його ключ (та сама гра клеймить знову); мертвий замінюємо.
            // Той самий uuid без ключа (скопійований uuid або наша ж перезапущена гра) чекає, поки старий запис застаріє.
            Host cur = hosts.compute(id, (k, old) -> {
                if (old == null || !old.alive(now)) return mine;
                if (old.player.equals(who) && !old.key.isEmpty() && old.key.equals(theirKey)) return mine.keyed(old.key);
                return old;
            });
            boolean sameButLocked = cur != mine && cur.player.equals(who);
            Log.info("Authority claim @ by @ from @ -> @ | prev=@", id, shortId(mine.player), mine.ip, cur == mine ? "HOST" : "client",
                prev == null ? "none" : shortId(prev.player) + " age=" + (now - prev.lastSeen) + "ms ready=" + prev.ready);

            if (cur == mine) json(ex, 200, "{\"role\":\"host\",\"key\":" + q(mine.key) + "}");
            else if (sameButLocked) json(ex, 200, "{\"role\":\"wait\",\"state\":\"lost\",\"retry\":" + cur.secondsToFree(now) + "}");
            else if (cur.stateAt(now) == OK) json(ex, 200, "{\"role\":\"client\",\"ip\":" + q(cur.ip) + ",\"port\":" + cur.port + "}");
            // Host is silent: neither join it nor take the sector, wait — Хост мовчить: ні підключатись, ні забирати сектор — чекати
            else json(ex, 200, "{\"role\":\"wait\",\"state\":" + q(STATE_NAMES[cur.stateAt(now)]) + ",\"retry\":" + cur.secondsToFree(now) + "}");

        } else if (post && action.equals("heartbeat")) {
            // Body (new clients): the sector's players [{uuid, ip, name, pid}], the host himself included — Тіло (нові клієнти): гравці сектора [{uuid, ip, name}]
            byte[] hb = readBody(ex);
            if (removed(player)) {
                hosts.computeIfPresent(id, (k, h) -> h.player.equals(player) ? null : h);
                sectorPlayers.remove(id);
                send(ex, 403, ""); // the host himself was removed — видалили самого хоста
                return;
            }
            if (!isHost(id, player, key)) {
                send(ex, 404, ""); // not the host (or wrong key): claim again — не хост (або не той ключ): клейм знову
                return;
            }
            // ?loading=1: still downloading / loading the sector — keep the claim alive, not ready yet
            // — ?loading=1: ще завантажує сектор — тримаємо клейм живим, але ще не готовий
            if (query(ex.getRequestURI(), "loading").equals("1")) {
                long t = System.currentTimeMillis();
                hosts.computeIfPresent(id, (k, h) -> h.player.equals(player) && h.key.equals(key) ? h.touch(t) : h);
                send(ex, 200, "");
                return;
            }
            if (hb.length > 0) {
                try {
                    List<String[]> list = new ArrayList<>();
                    for (JsonValue c = new JsonReader().parse(new String(hb, StandardCharsets.UTF_8)).child; c != null && list.size() < 64; c = c.next) {
                        list.add(new String[]{c.getString("uuid", ""), c.getString("ip", ""), cut(c.getString("name", "")), String.valueOf(c.getInt("pid", -1))});
                    }
                    sectorPlayers.put(id, list);
                } catch (Throwable ignored) {
                }
            }
            boolean[] ok = {false};
            long now = System.currentTimeMillis();
            hosts.computeIfPresent(id, (k, h) -> {
                if (!h.player.equals(player) || !h.key.equals(key)) return h;
                ok[0] = true;
                return h.beat(now);
            });
            // 404 = you are not the host anymore (Authority restarted or sector taken), claim again
            // — 404 = ти тут більше не хост (Authority перезапустився або сектор забрали), заклейм знову
            if (!ok[0]) {
                send(ex, 404, "");
                return;
            }
            // Players of this sector to throw out — Гравці цього сектора, яких треба викинути
            StringBuilder kb = new StringBuilder("{\"kick\":[");
            boolean first = true;
            for (String[] pl : sectorPlayers.getOrDefault(id, Collections.emptyList())) {
                if (!removed(pl[0]) && !bannedIps.contains(pl[1])) continue;
                if (!first) kb.append(',');
                first = false;
                kb.append(q(pl[0]));
            }
            json(ex, 200, kb.append("]}").toString());

        } else if (post && action.equals("release")) {
            boolean[] own = {false};
            hosts.computeIfPresent(id, (k, h) -> {
                if (!h.player.equals(player) || !h.key.equals(key)) return h;
                own[0] = true;
                return null;
            });
            if (own[0]) sectorPlayers.remove(id);
            Log.info("Authority release @ by @ -> @", id, shortId(player), own[0] ? "REMOVED" : "ignored");
            send(ex, 200, "");

        } else if (post && action.equals("save")) {
            byte[] b = readBody(ex); // body first, then rights (client gets a clean answer) — спершу тіло, потім права (клієнт отримає чисту відповідь)
            if (denyNonHost(ex, id, player, key)) return;
            // A real Mindustry save (zlib stream starting with MSAV), and within the disk quota
            // — Справжній сейв Mindustry (zlib-потік, що починається з MSAV), і в межах квоти диска
            if (!isMsav(b)) {
                send(ex, 400, "");
                return;
            }
            if (savesSize(id) + b.length > SAVES_QUOTA) {
                Log.warn("Aurora Authority: save of @ refused, saves quota (@ MB) is full", id, SAVES_QUOTA / 1024 / 1024);
                send(ex, 507, "");
                return;
            }
            writeAtomic(dir().child(id + ".msav"), b);
            saves.add(id);
            // ?ops=a,b: spends already inside this save, acked together with it (no window where they could come back or be taken twice)
            // — ?ops=a,b: списання, які вже є в цьому сейві, підтверджуються разом з ним (нема вікна, де вони повернуться або знімуться двічі)
            String ops = query(ex.getRequestURI(), "ops");
            if (!ops.isEmpty()) {
                Set<String> ids = new HashSet<>(Arrays.asList(ops.split(",")));
                List<Op> list = pending.get(id);
                if (list != null && list.removeIf(o -> ids.contains(o.id))) saveEconomy();
            }
            send(ex, 200, "");

        } else if (get && action.equals("save")) {
            Fi f = dir().child(id + ".msav");
            if (!saves.contains(id) || !f.exists()) send(ex, 404, "");
            else sendBytes(ex, 200, f.readBytes());

        } else if (post && action.equals("info")) {
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (denyNonHost(ex, id, player, key)) return;
            // Must be a JSON object of sane depth: every player who enters this sector will load it
            // — Має бути JSON-об'єкт нормальної глибини: його завантажить кожен, хто зайде в цей сектор
            try {
                JsonValue v = new JsonReader().parse(s);
                if (v == null || !v.isObject() || mindustry.io.JsonIO.depth(v) > mindustry.io.JsonIO.maxRemoteDepth) throw new IllegalArgumentException();
            } catch (Throwable bad) {
                send(ex, 400, "");
                return;
            }
            putInfo(id, s);
            writeAtomic(dir().child(id + ".info"), s);
            send(ex, 200, "");

        } else if (get && action.equals("info")) {
            String s = infos.get(id);
            if (s == null) send(ex, 404, "");
            else json(ex, 200, s);

        } else if (post && action.equals("items")) {
            // Host pushes its core stock (every heartbeat) — Хост відправляє склад свого ядра (кожен heartbeat)
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (denyNonHost(ex, id, player, key)) return;
            stock.put(id, sanitizeStock(id, readCounts(new JsonReader().parse(s))));
            stockTime.put(id, System.currentTimeMillis());
            synchronized (stockLock) {
                stockLock.notifyAll();
            }
            saveEconomy();
            send(ex, 200, "");

        } else if (get && action.equals("wait")) {
            // Long poll: the host waits here; returns when something needs its stock — Long poll: хост чекає тут; повертається, коли комусь потрібен його склад
            if (denyNonHost(ex, id, player, key)) return;
            long since = Strings.parseLong(query(ex.getRequestURI(), "since"), -1);
            long[] v = wakeVer.computeIfAbsent(id, k -> new long[1]);
            synchronized (v) {
                // Something new already, or Authority restarted (counter below the host's): answer now
                // — Уже є нове, або Authority перезапустився (лічильник менший за хостовий): відповідаємо зараз
                if (v[0] != since) {
                    json(ex, 200, "{\"ver\":" + v[0] + "}");
                    return;
                }
                // Park the request without holding a thread: wake() or the sweeper answers it — Паркуємо запит без зайнятого потоку: відповість wake() або прибиральник
                ex.deferred = true;
                waiters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(new Waiter(ex, System.currentTimeMillis() + WAIT_MAX));
            }

        } else if (get && action.equals("pending")) {
            // Host takes spend ops queued for its sector — Хост забирає списання, що чекають на його сектор
            if (denyNonHost(ex, id, player, key)) return;
            json(ex, 200, opsJson(pending.getOrDefault(id, NONE)));

        } else if (post && action.equals("ack")) {
            // Host confirms ops applied to its core — Хост підтверджує, що списання знято з ядра
            if (denyNonHost(ex, id, player, key)) return;
            Set<String> ids = new HashSet<>(Arrays.asList(query(ex.getRequestURI(), "ids").split(",")));
            List<Op> list = pending.get(id);
            if (list != null && list.removeIf(o -> ids.contains(o.id))) saveEconomy();
            send(ex, 200, "");

        } else {
            send(ex, 404, "");
        }
    }

    /** 403 if the player is not this sector's host. True = denied — 403, якщо гравець не хост цього сектора. true = відмовлено. */
    static boolean denyNonHost(Exchange ex, String id, String player, String key) throws IOException {
        if (isHost(id, player, key)) return false;
        send(ex, 403, "");
        return true;
    }

    static boolean isHost(String id, String player, String key) {
        Host h = hosts.get(id);
        return h != null && h.player.equals(player) && !h.key.isEmpty() && h.key.equals(key);
    }

    static final java.security.SecureRandom keyRandom = new java.security.SecureRandom();

    /** Hex SHA-256 — Hex SHA-256. */
    public static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String newKey() {
        byte[] b = new byte[16];
        keyRandom.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
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
        if (list != null) for (Waiter w : list) w.ex.finish(200, ("{\"ver\":" + ver + "}").getBytes(StandardCharsets.UTF_8));
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
                    w.ex.finish(200, ("{\"ver\":" + ver + "}").getBytes(StandardCharsets.UTF_8));
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
    static synchronized void spend(Exchange ex) throws IOException {
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
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            String item = e.getKey();
            Map<String, Integer> per = avail.get(item);
            int total = 0;
            for (int a : per.values()) total += a;
            int left = e.getValue();
            for (Map.Entry<String, Integer> s : per.entrySet()) {
                if (left == 0) break;
                int take = Math.min((int) Math.ceil((double) e.getValue() * s.getValue() / total), Math.min(left, s.getValue()));
                if (take <= 0) continue;
                pending.computeIfAbsent(s.getKey(), k -> new CopyOnWriteArrayList<>()).add(new Op(opId + ":" + item, item, take));
                left -= take;
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

    // ---------- Research cost — Ціна досліджень ----------

    static volatile Map<String, Map<String, Integer>> costs;
    /** content -> its tech tree parent's content — вміст -> вміст його батька в дереві. */
    static volatile Map<String, String> parents;

    /**
     * Is the node's parent open: no parent, always unlocked, open on this planet, or a free node (items, sectors).
     * — Чи відкритий батько вузла: батька нема, завжди відкритий, відкритий на цій планеті, або безкоштовний вузол (предмети, сектори).
     */
    static boolean parentOpen(String name) {
        researchCost(name); // builds the maps — будує мапи
        String parent = parents.get(name);
        if (parent == null || unlocked.contains(parent) || researchCost(parent) == null) return true;
        var c = Vars.content.byName(parent);
        return c instanceof mindustry.ctype.UnlockableContent u && u.alwaysUnlocked;
    }

    /** Full item cost of researching this content, or null if it has none (items, sectors...) — Повна ціна дослідження в предметах, або null, якщо її нема (предмети, сектори...). */
    static Map<String, Integer> researchCost(String name) {
        Map<String, Map<String, Integer>> c = costs;
        if (c == null) {
            c = new HashMap<>();
            Map<String, String> par = new HashMap<>();
            for (mindustry.content.TechTree.TechNode node : mindustry.content.TechTree.all) {
                if (node.parent != null) par.putIfAbsent(node.content.name, node.parent.content.name);
                Map<String, Integer> m = new HashMap<>();
                for (mindustry.type.ItemStack s : node.requirements) if (s.amount > 0) m.merge(s.item.name, s.amount, Integer::sum);
                if (!m.isEmpty()) c.put(node.content.name, m);
            }
            parents = par;
            costs = c;
        }
        return c.get(name);
    }

    /** The planet host's own game (loopback) — Власна гра хоста планети (loopback). */
    static boolean fromThisPc(Exchange ex) {
        return ex.owner;
    }

    // ---------- Utils — Утиліти ----------

    /**
     * Write to a temp file, then replace the target in one move: a crash leaves the old file or the new one, never half of one.
     * — Запис у тимчасовий файл, потім заміна одним переміщенням: падіння лишає старий файл або новий, але ніколи не половину.
     */
    static void writeAtomic(Fi target, byte[] data) throws IOException {
        java.nio.file.Path to = target.file().toPath();
        java.nio.file.Path tmp = to.resolveSibling(target.name() + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".tmp");
        java.nio.file.Files.write(tmp, data);
        try {
            java.nio.file.Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            java.nio.file.Files.move(tmp, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    static void writeAtomic(Fi target, String text) throws IOException {
        writeAtomic(target, text.getBytes(StandardCharsets.UTF_8));
    }

    /** An IP literal (no name lookup) in loopback / LAN / Tailscale — IP-літерал (без DNS) з loopback / LAN / Tailscale. */
    static boolean privateLiteral(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")) return false;
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a.isLoopbackAddress() || isLan(a) || isTailscale(a);
        } catch (Exception e) {
            return false;
        }
    }

    /** Starts like a Mindustry save: zlib stream whose first bytes are "MSAV" — Починається як сейв Mindustry: zlib-потік, перші байти якого "MSAV". */
    static boolean isMsav(byte[] b) {
        try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(b))) {
            byte[] head = new byte[4];
            int n = 0;
            while (n < 4) {
                int r = in.read(head, n, 4 - n);
                if (r < 0) return false;
                n += r;
            }
            return head[0] == 'M' && head[1] == 'S' && head[2] == 'A' && head[3] == 'V';
        } catch (Throwable e) {
            return false;
        }
    }

    /** Bytes of all saves on disk except this sector's (it is being replaced) — Байти всіх сейвів на диску, крім цього сектора (його замінюють). */
    static long savesSize(String except) {
        long total = 0;
        for (Fi f : dir().list()) {
            if (f.name().endsWith(".msav") && !f.name().equals(except + ".msav")) total += f.length();
        }
        return total;
    }

    /** A real sector of a real planet: "<planet>-<index>" — Справжній сектор справжньої планети: "<планета>-<номер>". */
    static boolean validSector(String id) {
        if (!safe(id)) return false;
        int d = id.lastIndexOf('-');
        if (d <= 0) return false;
        mindustry.type.Planet pl = Vars.content.planet(id.substring(0, d));
        int n = Strings.parseInt(id.substring(d + 1), -1);
        return pl != null && n >= 0 && n < pl.sectors.size;
    }

    /** The id goes into a file name, so safe characters only — id іде в назву файла, тож лише безпечні символи. */
    static boolean safe(String id) {
        return id.matches("[A-Za-z0-9_\\-]{1,64}");
    }

    static String shortId(String player) {
        return player == null ? "?" : player.substring(0, Math.min(6, player.length()));
    }

    static String query(URI uri, String key) {
        String qs = uri.getRawQuery();
        if (qs == null) return "";
        for (String kv : qs.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                try {
                    return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** Max request body: a sector save is a few MB, the rest is KB — Максимум тіла запиту: сейв сектора — кілька МБ, решта — кілобайти. */
    static final int MAX_BODY = 64 * 1024 * 1024, MAX_INFO = 1024 * 1024, MAX_SMALL = 64 * 1024;

    /** Body limit by path: save 64 MB, info 1 MB, everything else 64 KB — Ліміт тіла за шляхом: сейв 64 МБ, info 1 МБ, решта 64 КБ. */
    static int bodyLimit(String path) {
        if (path.endsWith("/save")) return MAX_BODY;
        if (path.endsWith("/info")) return MAX_INFO;
        return MAX_SMALL;
    }

    // ---------- Flood limits (this PC is exempt) — Обмеження від флуду (цей ПК не обмежується) ----------

    static final int MAX_LINKS_PER_IP = 4, MAX_LINKS = 64;
    /** Requests per second per address, and the burst allowed — Запитів на секунду з однієї адреси і дозволений сплеск. */
    static final float RATE = 30f, BURST = 90f;
    static final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> linksPerIp = new ConcurrentHashMap<>();
    /** ip -> {tokens, last refill ms} — ip -> {токени, останнє поповнення мс} */
    static final ConcurrentHashMap<String, double[]> buckets = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<Connection, String> connIp = new ConcurrentHashMap<>();
    /** Bytes of unfinished requests across all links, and the cap — Байти незавершених запитів на всіх з'єднаннях і межа. */
    static final java.util.concurrent.atomic.AtomicLong assembling = new java.util.concurrent.atomic.AtomicLong();
    static final long MAX_ASSEMBLING = 160L * 1024 * 1024;

    static String ipOf(Connection c) {
        InetSocketAddress a = c.getRemoteAddressTCP();
        return a == null ? "" : plainAddr(a.getAddress());
    }

    /** An unknown (empty) address is never "this PC" — Невідома (порожня) адреса ніколи не "цей ПК". */
    static boolean isLoopback(String ip) {
        return ip != null && (ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1"));
    }

    static int totalLinks() {
        int n = 0;
        for (var v : linksPerIp.values()) n += v.get();
        return n;
    }

    /** Token bucket per address — Відро токенів на адресу. */
    static boolean allowRate(String ip) {
        double[] b = buckets.computeIfAbsent(ip, k -> new double[]{BURST, System.currentTimeMillis()});
        synchronized (b) {
            long now = System.currentTimeMillis();
            b[0] = Math.min(BURST, b[0] + (now - b[1]) / 1000.0 * RATE);
            b[1] = now;
            if (b[0] < 1) return false;
            b[0] -= 1;
            return true;
        }
    }

    static byte[] readBody(Exchange ex) throws IOException {
        if (ex.req.body.length > MAX_BODY) throw new IOException("request body too large");
        return ex.req.body;
    }

    static volatile Set<String> contentNames;

    /** Is this a real UnlockableContent name (keeps junk out of unlocked.txt) — Чи це справжній UnlockableContent (щоб у unlocked.txt не було сміття). */
    static boolean isContent(String name) {
        Set<String> set = contentNames;
        if (set == null) {
            Set<String> s = new HashSet<>();
            Vars.content.each(c -> {
                if (c instanceof mindustry.ctype.UnlockableContent u) s.add(u.name);
            });
            contentNames = set = s;
        }
        return set.contains(name);
    }

    /** JSON string with escaping — Рядок JSON з екрануванням. */
    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String countsJson(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append(q(k)).append(':').append(v);
        });
        return sb.append('}').toString();
    }

    static String opsJson(List<Op> ops) {
        StringBuilder sb = new StringBuilder("[");
        for (Op o : ops) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"id\":").append(q(o.id)).append(",\"item\":").append(q(o.item)).append(",\"amount\":").append(o.amount).append('}');
        }
        return sb.append(']').toString();
    }

    static void json(Exchange ex, int code, String body) throws IOException {
        sendBytes(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(Exchange ex, int code, String body) throws IOException {
        sendBytes(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(Exchange ex, int code, byte[] b) throws IOException {
        ex.code = code;
        ex.body = b;
    }

    // ---------- ArcNet glue — Склейка з ArcNet ----------

    /** Runs one request on a pool thread — Виконує один запит у потоці пулу. */
    static void serve(Connection c, int id, byte[] raw, String from) {
        Exchange ex;
        try {
            ex = new Exchange(c, id, AuroraWire.decodeReq(raw), from);
        } catch (Throwable e) {
            Log.warn("Aurora Authority: bad request from @", c);
            c.close(DcReason.error);
            return;
        }
        handle(ex);
    }

    /**
     * One request and its answer. The answer is sent on close(), or later through finish() for a parked /wait.
     * — Один запит і відповідь на нього. Відповідь іде на close(), або пізніше через finish() для запаркованого /wait.
     */
    static final class Exchange {
        final Connection conn;
        final int id;
        final AuroraWire.Req req;
        final InetSocketAddress remote;
        int code = 500;
        byte[] body = new byte[0];
        volatile boolean deferred;
        boolean done;
        /** Password part of the token — Частина токена з паролем. */
        final String password;
        /** The planet host's own game: loopback AND it knows the owner secret — Власна гра хоста планети: loopback І знає секрет власника. */
        final boolean owner;

        Exchange(Connection conn, int id, AuroraWire.Req req, String from) throws UnknownHostException {
            this.conn = conn;
            this.id = id;
            this.req = req;
            // The address captured when the request arrived, not re-read now: a closed link would report none,
            // and treating "none" as this PC let a request skip payment and bans
            // — Адреса, взята, коли запит прийшов, а не перечитана зараз: закрите з'єднання не дало б жодної,
            // а "жодна" як "цей ПК" дозволяла запиту обійти оплату й бани
            this.remote = new InetSocketAddress(InetAddress.getByName(from), 0);
            // token = password [\u0001 owner secret]. Loopback alone is not enough: a tunnel (playit.gg, ngrok...) makes
            // internet players look like 127.0.0.1 too
            // — token = пароль [\u0001 секрет власника]. Самого loopback замало: тунель (playit.gg, ngrok...) робить
            // гравців з інтернету теж схожими на 127.0.0.1
            String t = req.token == null ? "" : req.token;
            int cut = t.indexOf('\u0001');
            this.password = cut < 0 ? t : t.substring(0, cut);
            this.owner = cut >= 0 && isLoopback(plainAddr(remote.getAddress()))
                && java.security.MessageDigest.isEqual(t.substring(cut + 1).getBytes(StandardCharsets.UTF_8), AuroraNet.ownerSecret().getBytes(StandardCharsets.UTF_8));
        }

        InetSocketAddress getRemoteAddress() {
            return remote;
        }

        /** Path with query, for query() — Шлях із query, для query(). */
        URI getRequestURI() {
            return URI.create(req.path);
        }

        void close() {
            if (!deferred) finish(code, body);
        }

        void finish(int code, byte[] body) {
            synchronized (this) {
                if (done) return;
                done = true;
            }
            try {
                AuroraWire.send(conn, AuroraWire.RESP, id, AuroraWire.encodeResp(code, body));
            } catch (IOException e) {
                Log.warn("Aurora Authority: could not answer @ (@)", conn, e.getMessage());
            }
        }
    }
}
