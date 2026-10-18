package mindustry;

import arc.*;
import arc.files.*;
import arc.util.*;
import arc.util.serialization.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Built-in Authority: the "brain" of a shared Aurora planet, a plain HTTP server inside the planet host's game.
 * Keeps who hosts which sector, sector saves, shared research and the shared item stock.
 * — Вбудований Authority: "мозок" спільної планети Aurora, простий HTTP-сервер у грі хоста планети.
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

        Host(String ip, int port, String player, long lastSeen, boolean ready) {
            this.ip = ip;
            this.port = port;
            this.player = player;
            this.lastSeen = lastSeen;
            this.ready = ready;
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
            return new Host(ip, port, player, now, true);
        }

        /** Player UUID is never sent: it is the player's "key" to the sector — UUID гравця не віддаємо: це його "ключ" до сектора. */
        String json(long now) {
            return "{\"ip\":" + q(ip) + ",\"port\":" + port + ",\"lastSeen\":" + lastSeen + ",\"ready\":" + ready
                + ",\"state\":" + q(STATE_NAMES[stateAt(now)]) + "}";
        }
    }

    static HttpServer server;
    static final ConcurrentHashMap<String, Host> hosts = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, byte[]> saves = new ConcurrentHashMap<>();
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
        return server == null ? -1 : server.getAddress().getPort();
    }

    // ---------- Access scope — Хто може зайти ----------

    public static final int SCOPE_LOCAL = 0, SCOPE_LAN = 1, SCOPE_TAILSCALE = 2;

    /**
     * 0 this PC only, 1 LAN + Tailscale (default), 2 Tailscale only. The internet never.
     * — 0 лише цей ПК, 1 LAN + Tailscale (за замовчуванням), 2 лише Tailscale. Інтернет — ніколи.
     */
    public static int scope() {
        int def = Core.settings.getBool("aurora-public", true) ? SCOPE_LAN : SCOPE_LOCAL; // old setting — старе налаштування
        return Core.settings.getInt("aurora-scope", def);
    }

    public static void setScope(int s) {
        Core.settings.put("aurora-scope", s);
    }

    /** Is the planet open to other players — Чи відкрита планета для інших. */
    public static boolean isPublic() {
        return scope() != SCOPE_LOCAL;
    }

    /** Tailscale: 100.64.0.0/10 and fd7a:115c:a1e0::/48 — Tailscale: 100.64.0.0/10 і fd7a:115c:a1e0::/48. */
    static boolean isTailscale(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        return b.length == 16 && (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0x5c
            && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
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
        switch (scope()) {
            case SCOPE_LAN: return isLan(a) || isTailscale(a);
            case SCOPE_TAILSCALE: return isTailscale(a);
            default: return false;
        }
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

            HttpServer s = HttpServer.create(new InetSocketAddress(Core.settings.getString("aurora-bind", "0.0.0.0"), port), 0);
            s.createContext("/", AuroraAuthority::handle);
            s.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "aurora-authority");
                t.setDaemon(true);
                return t;
            }));
            s.start();
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
        server.stop(0);
        server = null;
        AuroraNet.localAuthority = false;
        hosts.clear();
    }

    // ---------- Disk — Диск ----------

    static void load() {
        try {
            Fi d = dir();
            if (!d.exists()) return;
            for (Fi f : d.list()) {
                String n = f.name();
                if (n.endsWith(".msav")) saves.put(n.substring(0, n.length() - 5), f.readBytes());
                else if (n.endsWith(".info")) putInfo(n.substring(0, n.length() - 5), f.readString());
            }
            Fi u = d.child("unlocked.txt");
            if (u.exists()) {
                for (String line : u.readString().split("\n")) {
                    if (!line.trim().isEmpty()) unlocked.add(line.trim());
                }
            }
            loadEconomy();
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

    static void saveUnlocks() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : unlocked) sb.append(s).append('\n');
            dir().child("unlocked.txt").writeString(sb.toString());
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
            Fi tmp = dir().child("economy.json.tmp");
            tmp.writeString(sb.toString());
            tmp.moveTo(dir().child("economy.json"));
        } catch (Throwable e) {
            Log.err("Aurora economy save failed", e);
        }
    }

    // ---------- HTTP ----------

    static void handle(HttpExchange ex) {
        try {
            // Who may knock: this PC / LAN+Tailscale / Tailscale only — Хто може стукати: цей ПК / LAN+Tailscale / лише Tailscale
            if (!allowed(ex.getRemoteAddress().getAddress())) {
                send(ex, 403, "");
                return;
            }

            String token = Core.settings.getString("aurora-token", "");
            if (!token.isEmpty() && !token.equals(ex.getRequestHeaders().getFirst("X-Aurora-Token"))) {
                send(ex, 401, "");
                return;
            }

            URI uri = ex.getRequestURI();
            String m = ex.getRequestMethod();
            String[] p = uri.getPath().split("/");
            boolean get = m.equals("GET");

            // /version is open; everything else needs the same protocol — /version відкритий; решта — лише з тією самою версією протоколу
            if (get && p.length == 2 && p[1].equals("version")) {
                json(ex, 200, "{\"proto\":" + AuroraNet.PROTOCOL + "}");
                return;
            }
            if (!String.valueOf(AuroraNet.PROTOCOL).equals(ex.getRequestHeaders().getFirst("X-Aurora-Proto"))) {
                json(ex, 426, "{\"proto\":" + AuroraNet.PROTOCOL + "}");
                return;
            }

            String path = p.length >= 2 ? p[1] : "";

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

            } else if (get && p.length == 2 && path.equals("mode")) {
                // Is the planet host in Aurora right now (not normal campaign / own game) — Чи хост планети зараз в Aurora (а не у звичайній кампанії / своїй грі)
                json(ex, 200, "{\"active\":" + (Vars.campaignSlot == Vars.slotAurora && isPublic()) + "}");

            } else if (get && p.length == 3 && path.equals("sectors") && p[2].equals("state")) {
                // {"id": bits}: 1 core, 2 waves still running, 4 attacking an enemy base — 1 ядро, 2 хвилі ще йдуть, 4 атака на ворожу базу
                StringBuilder sb = new StringBuilder("{");
                for (String s : saves.keySet()) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s)).append(':').append(infoBits.getOrDefault(s, 1 | 2));
                }
                json(ex, 200, sb.append('}').toString());

            } else if (p.length == 4 && path.equals("sector") && safe(p[2])) {
                sector(ex, m, p[2], p[3], query(uri, "player"));

            } else if (get && p.length == 2 && path.equals("items")) {
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

    /** /sector/{id}/{action} */
    static void sector(HttpExchange ex, String m, String id, String action, String player) throws Exception {
        boolean get = m.equals("GET"), post = m.equals("POST"), put = m.equals("PUT");

        if (post && action.equals("claim")) {
            JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            Host mine = new Host(j.getString("ip"), j.getInt("port"), j.getString("player"), now, false);
            Host prev = hosts.get(id);
            // Another player's live host stays; our own old entry or a dead one is replaced — Чужий живий хост лишається; свій старий чи мертвий — замінюємо
            Host cur = hosts.compute(id, (k, old) -> old != null && !old.player.equals(mine.player) && old.alive(now) ? old : mine);
            Log.info("Authority claim @ by @ from @ -> @ | prev=@", id, shortId(mine.player), mine.ip, cur == mine ? "HOST" : "client",
                prev == null ? "none" : shortId(prev.player) + " age=" + (now - prev.lastSeen) + "ms ready=" + prev.ready);

            if (cur == mine) json(ex, 200, "{\"role\":\"host\"}");
            else if (cur.stateAt(now) == OK) json(ex, 200, "{\"role\":\"client\",\"ip\":" + q(cur.ip) + ",\"port\":" + cur.port + "}");
            // Host is silent: neither join it nor take the sector, wait — Хост мовчить: ні підключатись, ні забирати сектор — чекати
            else json(ex, 200, "{\"role\":\"wait\",\"state\":" + q(STATE_NAMES[cur.stateAt(now)]) + ",\"retry\":" + cur.secondsToFree(now) + "}");

        } else if (post && action.equals("heartbeat")) {
            boolean[] ok = {false};
            long now = System.currentTimeMillis();
            hosts.computeIfPresent(id, (k, h) -> {
                if (!h.player.equals(player)) return h;
                ok[0] = true;
                return h.beat(now);
            });
            // 404 = you are not the host anymore (Authority restarted or sector taken), claim again
            // — 404 = ти тут більше не хост (Authority перезапустився або сектор забрали), заклейм знову
            send(ex, ok[0] ? 200 : 404, "");

        } else if (post && action.equals("release")) {
            boolean[] own = {false};
            hosts.computeIfPresent(id, (k, h) -> {
                if (!h.player.equals(player)) return h;
                own[0] = true;
                return null;
            });
            Log.info("Authority release @ by @ -> @", id, shortId(player), own[0] ? "REMOVED" : "ignored");
            send(ex, 200, "");

        } else if (put && action.equals("save")) {
            byte[] b = readBody(ex); // body first, then rights (client gets a clean answer) — спершу тіло, потім права (клієнт отримає чисту відповідь)
            if (denyNonHost(ex, id, player)) return;
            saves.put(id, b);
            dir().child(id + ".msav").writeBytes(b);
            send(ex, 200, "");

        } else if (get && action.equals("save")) {
            byte[] b = saves.get(id);
            if (b == null) send(ex, 404, "");
            else sendBytes(ex, 200, "application/octet-stream", b);

        } else if (put && action.equals("info")) {
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (denyNonHost(ex, id, player)) return;
            putInfo(id, s);
            dir().child(id + ".info").writeString(s);
            send(ex, 200, "");

        } else if (get && action.equals("info")) {
            String s = infos.get(id);
            if (s == null) send(ex, 404, "");
            else json(ex, 200, s);

        } else if (put && action.equals("items")) {
            // Host pushes its core stock (every heartbeat) — Хост відправляє склад свого ядра (кожен heartbeat)
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (denyNonHost(ex, id, player)) return;
            stock.put(id, readCounts(new JsonReader().parse(s)));
            saveEconomy();
            send(ex, 200, "");

        } else if (get && action.equals("pending")) {
            // Host takes spend ops queued for its sector — Хост забирає списання, що чекають на його сектор
            if (denyNonHost(ex, id, player)) return;
            json(ex, 200, opsJson(pending.getOrDefault(id, NONE)));

        } else if (post && action.equals("ack")) {
            // Host confirms ops applied to its core — Хост підтверджує, що списання знято з ядра
            if (denyNonHost(ex, id, player)) return;
            Set<String> ids = new HashSet<>(Arrays.asList(query(ex.getRequestURI(), "ids").split(",")));
            List<Op> list = pending.get(id);
            if (list != null && list.removeIf(o -> ids.contains(o.id))) saveEconomy();
            send(ex, 200, "");

        } else {
            send(ex, 404, "");
        }
    }

    /** 403 if the player is not this sector's host. True = denied — 403, якщо гравець не хост цього сектора. true = відмовлено. */
    static boolean denyNonHost(HttpExchange ex, String id, String player) throws IOException {
        if (isHost(id, player)) return false;
        send(ex, 403, "");
        return true;
    }

    static boolean isHost(String id, String player) {
        Host h = hosts.get(id);
        return h != null && h.player.equals(player);
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
    static synchronized void spend(HttpExchange ex) throws IOException {
        JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
        String opId = j.getString("id");
        long now = System.currentTimeMillis();
        doneOps.values().removeIf(t -> now - t > DONE_OPS_TTL);
        if (doneOps.putIfAbsent(opId, now) != null) { // retry of a handled request — повтор уже обробленого запиту
            send(ex, 200, "");
            return;
        }

        Map<String, Integer> need = new HashMap<>();
        for (JsonValue c = j.get("items").child; c != null; c = c.next) {
            if (c.asInt() > 0) need.put(c.name, c.asInt());
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
        send(ex, 200, "");
    }

    // ---------- Utils — Утиліти ----------

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
    static final int MAX_BODY = 64 * 1024 * 1024;

    static byte[] readBody(HttpExchange ex) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        InputStream in = ex.getRequestBody();
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) throw new IOException("request body too large");
        }
        return out.toByteArray();
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

    static void json(HttpExchange ex, int code, String body) throws IOException {
        sendBytes(ex, code, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(HttpExchange ex, int code, String body) throws IOException {
        sendBytes(ex, code, "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(HttpExchange ex, int code, String type, byte[] b) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            try (OutputStream o = ex.getResponseBody()) {
                o.write(b);
            }
        }
    }
}
