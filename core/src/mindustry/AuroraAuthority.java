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
 * Вбудований Authority: те саме, що Ktor Main.kt, але всередині гри, на простому HTTP.
 */
public class AuroraAuthority {
    /**
     * Життєвий цикл хоста сектора після останнього heartbeat (heartbeat іде кожні 10 с):
     *   0-15 с  OK       - хост живий, інші заходять до нього клієнтами;
     *  15-30 с  LOST     - зв'язок пропав, перевіряємо: ніхто не може зайти чи забрати сектор;
     *  30-45 с  ADAPT    - 15 с на оновлення сектора: сектор ще закритий (повернутись може лише сам хост);
     *  45+  с   FREE     - сектор відкритий, перший, хто зайде, стане хостом з останнього сейву.
     */
    static final long OK_TIMEOUT = 15_000L, LOST_TIMEOUT = 30_000L, ADAPT_TIMEOUT = 45_000L;
    static final long HOST_TIMEOUT = ADAPT_TIMEOUT;
    static final long PENDING_TIMEOUT = 12_000L;
    static final int OK = 0, LOST = 1, ADAPT = 2, FREE = 3;

    static class Host {
        final String ip, player;
        final int port;
        final long lastSeen;
        final boolean ready;   // true після першого heartbeat: хост уже слухає порт

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

        /** скільки секунд до відкриття сектора */
        int secondsToFree(long now) {
            return (int)Math.max(1, ((ready ? ADAPT_TIMEOUT : PENDING_TIMEOUT) - (now - lastSeen) + 999) / 1000);
        }

        boolean alive(long now) {
            return stateAt(now) != FREE;
        }

        String json() {
            //UUID гравця не віддаємо: це його "ключ" до сектора (save/release/pending перевіряються по ньому)
            int st = stateAt(System.currentTimeMillis());
            return "{\"ip\":" + q(ip) + ",\"port\":" + port + ",\"lastSeen\":" + lastSeen + ",\"ready\":" + ready
                + ",\"state\":" + q(st == OK ? "ok" : st == LOST ? "lost" : st == ADAPT ? "adapt" : "free") + "}";
        }
    }

    static HttpServer server;
    static final ConcurrentHashMap<String, Host> hosts = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, byte[]> saves = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> infos = new ConcurrentHashMap<>();
    static final Set<String> unlocked = ConcurrentHashMap.newKeySet();
    /** хто що дослідив (лише в пам'яті, для сповіщень) */
    static final ConcurrentHashMap<String, String> unlockedBy = new ConcurrentHashMap<>();

    public static boolean running() {
        return server != null;
    }

    /** Хто може достукатись до Authority. */
    public static final int SCOPE_LOCAL = 0, SCOPE_LAN = 1, SCOPE_TAILSCALE = 2;

    /** 0 = лише цей ПК, 1 = локальна мережа + Tailscale (за замовчуванням), 2 = лише Tailscale. Інтернет - ніколи. */
    public static int scope() {
        int def = Core.settings.getBool("aurora-public", true) ? SCOPE_LAN : SCOPE_LOCAL; //старе налаштування
        return Core.settings.getInt("aurora-scope", def);
    }

    public static void setScope(int s) {
        Core.settings.put("aurora-scope", s);
    }

    /** Чи відкрита планета для інших гравців. */
    public static boolean isPublic() {
        return scope() != SCOPE_LOCAL;
    }

    public static void setPublic(boolean v) {
        setScope(v ? SCOPE_LAN : SCOPE_LOCAL);
    }

    /** Tailscale: 100.64.0.0/10 і fd7a:115c:a1e0::/48. */
    static boolean isTailscale(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64;
        return b.length == 16 && (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0x5c
            && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
    }

    /** Приватні адреси: 10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA fc00::/7. */
    static boolean isLan(InetAddress a) {
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /** Чи пускати запит з цієї адреси. Публічні адреси інтернету не пускаються ніколи. */
    static boolean allowed(InetAddress a) {
        if (a.isLoopbackAddress()) return true;
        //IPv4, загорнута в IPv6 (::ffff:x.x.x.x)
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
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

    public static int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public static int liveCount() {
        long now = System.currentTimeMillis();
        int n = 0;
        for (Host h : hosts.values()) if (h.alive(now)) n++;
        return n;
    }

    /** "сектор - ip:port" для кожного живого хоста */
    public static java.util.List<String> liveList() {
        long now = System.currentTimeMillis();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Map.Entry<String, Host> e : hosts.entrySet()) {
            if (e.getValue().alive(now)) out.add(e.getKey() + "  -  " + e.getValue().ip + ":" + e.getValue().port);
        }
        java.util.Collections.sort(out);
        return out;
    }

    public static int savedCount() {
        return saves.size();
    }

    public static int unlockCount() {
        return unlocked.size();
    }

    static Fi dir() {
        return Core.settings.getDataDirectory().child("aurora-authority");
    }

    /**
     * Запускає сервер. true, якщо працює (вже був або щойно стартував).
     */
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
            AuroraNet.localAuthority = true;
            //адресу Authority друга ("aurora-authority") не чіпаємо: AuroraNet.authority() сам бере 127.0.0.1 на своїй планеті

            return true;
        } catch (java.net.BindException e) {
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

    // ---------- диск ----------

    static void load() {
        try {
            Fi d = dir();
            if (!d.exists()) return;
            for (Fi f : d.list()) {
                String n = f.name();
                if (n.endsWith(".msav")) saves.put(n.substring(0, n.length() - 5), f.readBytes());
                else if (n.endsWith(".info")) infos.put(n.substring(0, n.length() - 5), f.readString());
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

    // ---------- економіка (склад секторів і черга списань) на диску ----------

    /** economy.json: {"stock":{сектор:{предмет:к-сть}}, "pending":{сектор:[{id,item,amount}]}} */
    static void loadEconomy() {
        stock.clear();
        pending.clear();
        Fi f = dir().child("economy.json");
        if (f.exists()) {
            try {
                JsonValue root = new JsonReader().parse(f.readString());
                JsonValue st = root.get("stock");
                if (st != null) for (JsonValue sec = st.child; sec != null; sec = sec.next) {
                    Map<String, Integer> m = new ConcurrentHashMap<>();
                    for (JsonValue it = sec.child; it != null; it = it.next) m.put(it.name, it.asInt());
                    stock.put(sec.name, m);
                }
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
        //сектори без запису в economy.json: склад з останнього info (поле items = ядро на момент збереження)
        for (Map.Entry<String, String> e : infos.entrySet()) {
            if (stock.containsKey(e.getKey())) continue;
            try {
                JsonValue items = new JsonReader().parse(e.getValue()).get("items");
                if (items == null) continue;
                Map<String, Integer> m = new ConcurrentHashMap<>();
                for (JsonValue it = items.child; it != null; it = it.next) if (it.isNumber()) m.put(it.name, it.asInt());
                if (!m.isEmpty()) stock.put(e.getKey(), m);
            } catch (Throwable ignored) {
            }
        }
        Log.info("Aurora economy: @ sectors with stock, @ with pending ops", stock.size(), pending.size());
    }

    static synchronized void saveEconomy() {
        try {
            StringBuilder sb = new StringBuilder("{\"stock\":{");
            boolean first = true;
            for (Map.Entry<String, Map<String, Integer>> e : stock.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(q(e.getKey())).append(":{");
                boolean f2 = true;
                for (Map.Entry<String, Integer> it : e.getValue().entrySet()) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append(q(it.getKey())).append(':').append(it.getValue());
                }
                sb.append('}');
            }
            sb.append("},\"pending\":{");
            first = true;
            for (Map.Entry<String, CopyOnWriteArrayList<Op>> e : pending.entrySet()) {
                if (e.getValue().isEmpty()) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append(q(e.getKey())).append(":[");
                boolean f2 = true;
                for (Op o : e.getValue()) {
                    if (!f2) sb.append(',');
                    f2 = false;
                    sb.append("{\"id\":").append(q(o.id)).append(",\"item\":").append(q(o.item)).append(",\"amount\":").append(o.amount).append('}');
                }
                sb.append(']');
            }
            sb.append("}}");
            //спершу у тимчасовий файл, потім заміна - щоб при краші не лишився обрізаний JSON
            Fi tmp = dir().child("economy.json.tmp");
            tmp.writeString(sb.toString());
            tmp.moveTo(dir().child("economy.json"));
        } catch (Throwable e) {
            Log.err("Aurora economy save failed", e);
        }
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

    // ---------- HTTP ----------

    static void handle(HttpExchange ex) {
        try {
            //хто може стукати: цей ПК / LAN+Tailscale / лише Tailscale; інтернет - ніколи
            if (!allowed(ex.getRemoteAddress().getAddress())) {
                send(ex, 403, "text/plain", "");
                return;
            }

            String token = Core.settings.getString("aurora-token", "");
            if (!token.isEmpty() && !token.equals(ex.getRequestHeaders().getFirst("X-Aurora-Token"))) {
                send(ex, 401, "text/plain", "");
                return;
            }

            URI uri = ex.getRequestURI();
            String m = ex.getRequestMethod();
            String[] p = uri.getPath().split("/");
            String player = query(uri, "player");

            //версія: /version відкритий для всіх, решта - лише з тією самою версією протоколу
            if (m.equals("GET") && p.length == 2 && p[1].equals("version")) {
                json(ex, 200, "{\"proto\":" + AuroraNet.PROTOCOL + "}");
                return;
            }
            if (!String.valueOf(AuroraNet.PROTOCOL).equals(ex.getRequestHeaders().getFirst("X-Aurora-Proto"))) {
                json(ex, 426, "{\"proto\":" + AuroraNet.PROTOCOL + "}");
                return;
            }

            if (m.equals("GET") && p.length == 2 && p[1].equals("unlocks") && "1".equals(query(uri, "full"))) {
                // {"назва":"хто дослідив", ...}; "" якщо невідомо
                StringBuilder sb = new StringBuilder("{");
                for (String s : unlocked) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s)).append(':').append(q(unlockedBy.getOrDefault(s, "")));
                }
                json(ex, 200, sb.append('}').toString());

            } else if (m.equals("GET") && p.length == 2 && p[1].equals("unlocks")) {
                StringBuilder sb = new StringBuilder("[");
                for (String s : unlocked) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s));
                }
                json(ex, 200, sb.append(']').toString());

            } else if (m.equals("GET") && p.length == 3 && p[1].equals("unlock")) {
                String by = query(uri, "by");
                if (!isContent(p[2])) {
                    send(ex, 400, "text/plain", "");
                    return;
                }
                if (by.length() > 40) by = by.substring(0, 40);
                if (unlocked.add(p[2])) {
                    if (by != null && !by.isEmpty()) unlockedBy.put(p[2], by);
                    saveUnlocks();
                }
                json(ex, 200, "{\"name\":" + q(p[2]) + ",\"unlocked\":true}");

            } else if (m.equals("GET") && p.length == 3 && p[1].equals("unlocked")) {
                json(ex, 200, "{\"name\":" + q(p[2]) + ",\"unlocked\":" + unlocked.contains(p[2]) + "}");

            } else if (m.equals("GET") && p.length == 2 && p[1].equals("sectors")) {
                long now = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder("{");
                for (Map.Entry<String, Host> e : hosts.entrySet()) {
                    if (!e.getValue().alive(now)) continue;
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(e.getKey())).append(':').append(e.getValue().json());
                }
                json(ex, 200, sb.append('}').toString());

            } else if (m.equals("GET") && p.length == 2 && p[1].equals("mode")) {
                // чи хост планети зараз у режимі Aurora (а не у звичайній кампанії / своїй грі)
                json(ex, 200, "{\"active\":" + (mindustry.Vars.campaignSlot == 1 && isPublic()) + "}");

            } else if (m.equals("GET") && p.length == 3 && p[1].equals("sectors") && p[2].equals("state")) {
                // {"id": біти}: 1 = є ядро, 2 = хвилі ще йдуть, 4 = атака на ворожу базу
                // (у JSON SectorInfo поля зі значенням за замовчуванням пропущені: hasCore=true, waves=true, attack=false)
                StringBuilder sb = new StringBuilder("{");
                for (String s : saves.keySet()) {
                    int bits = 1 | 2;
                    String info = infos.get(s);
                    try {
                        if (info != null) {
                            JsonValue j = new JsonReader().parse(info);
                            bits = (j.getBoolean("hasCore", true) ? 1 : 0) | (j.getBoolean("waves", true) ? 2 : 0) | (j.getBoolean("attack", false) ? 4 : 0);
                        }
                    } catch (Throwable ignored) {
                    }
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s)).append(':').append(bits);
                }
                json(ex, 200, sb.append('}').toString());

            } else if (m.equals("GET") && p.length == 3 && p[1].equals("sectors") && p[2].equals("captured")) {
                // захоплені = є сейв і в info ядро ще живе (hasCore за замовчуванням true)
                StringBuilder sb = new StringBuilder("[");
                for (String s : saves.keySet()) {
                    String info = infos.get(s);
                    boolean core = true;
                    try {
                        if (info != null) core = new JsonReader().parse(info).getBoolean("hasCore", true);
                    } catch (Throwable ignored) {
                    }
                    if (!core) continue;
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s));
                }
                json(ex, 200, sb.append(']').toString());

            } else if (m.equals("GET") && p.length == 3 && p[1].equals("sectors") && p[2].equals("known")) {
                StringBuilder sb = new StringBuilder("[");
                for (String s : saves.keySet()) {
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(s));
                }
                json(ex, 200, sb.append(']').toString());

            } else if (p.length == 4 && p[1].equals("sector") && safe(p[2])) {
                sector(ex, m, p[2], p[3], player);
            } else if (m.equals("GET") && p.length == 2 && p[1].equals("items")) {
                Map<String, Integer> sum = new TreeMap<>();
                for (String sec : stock.keySet()) {
                    for (String item : stock.get(sec).keySet()) sum.merge(item, shown(sec, item), Integer::sum);
                }
                StringBuilder sb = new StringBuilder("{");
                for (Map.Entry<String, Integer> e : sum.entrySet()) {
                    if (e.getValue() <= 0) continue;
                    if (sb.length() > 1) sb.append(',');
                    sb.append(q(e.getKey())).append(':').append(e.getValue());
                }
                json(ex, 200, sb.append('}').toString());

            } else if (m.equals("POST") && p.length == 2 && p[1].equals("spend")) {
                spend(ex);

            } else {
                send(ex, 404, "text/plain", "");
            }
        } catch (Throwable e) {
            Log.err("Aurora Authority request failed", e);
            try {
                send(ex, 400, "text/plain", "");
            } catch (Throwable ignored) {
            }
        } finally {
            ex.close();
        }
    }

    static void sector(HttpExchange ex, String m, String id, String action, String player) throws Exception {
        if (m.equals("POST") && action.equals("claim")) {
            JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            Host mine = new Host(j.getString("ip"), j.getInt("port"), j.getString("player"), now, false);
            Host prev = hosts.get(id);
            Host cur = hosts.compute(id, (k, old) -> old != null && !old.player.equals(mine.player) && old.alive(now) ? old : mine);
            String who = mine.player.substring(0, Math.min(6, mine.player.length()));
            Log.info("Authority claim @ by @ from @ -> @ | prev=@", id, who, mine.ip, cur == mine ? "HOST" : "client",
                    prev == null ? "none" : prev.player.substring(0, Math.min(6, prev.player.length())) + " age=" + (now - prev.lastSeen) + "ms ready=" + prev.ready);
            if (cur == mine) json(ex, 200, "{\"role\":\"host\"}");
            else if (cur.stateAt(now) == OK) json(ex, 200, "{\"role\":\"client\",\"ip\":" + q(cur.ip) + ",\"port\":" + cur.port + "}");
                //хост мовчить: ні підключатись до нього, ні забирати сектор - чекати
            else json(ex, 200, "{\"role\":\"wait\",\"state\":" + q(cur.stateAt(now) == LOST ? "lost" : "adapt") + ",\"retry\":" + cur.secondsToFree(now) + "}");

        } else if (m.equals("POST") && action.equals("heartbeat")) {
            boolean[] ok = {false};
            hosts.computeIfPresent(id, (k, h) -> {
                if (!h.player.equals(player)) return h;
                ok[0] = true;
                return new Host(h.ip, h.port, h.player, System.currentTimeMillis(), true);
            });
            //404 = ти тут більше не хост (Authority перезапустився або сектор забрали) - клієнт має перезаклеймити
            send(ex, ok[0] ? 200 : 404, "text/plain", "");

        } else if (m.equals("POST") && action.equals("release")) {
            Host h0 = hosts.get(id);
            boolean own = h0 != null && h0.player.equals(player);
            hosts.computeIfPresent(id, (k, h) -> h.player.equals(player) ? null : h);
            Log.info("Authority release @ by @ -> @", id, player.substring(0, Math.min(6, player.length())), own ? "REMOVED" : "ignored");
            send(ex, 200, "text/plain", "");

        } else if (m.equals("PUT") && action.equals("save")) {
            byte[] b = readBody(ex);                       // спершу читаємо тіло
            if (!isHost(id, player)) {
                send(ex, 403, "text/plain", "");
                return;
            }
            saves.put(id, b);
            dir().child(id + ".msav").writeBytes(b);
            send(ex, 200, "text/plain", "");

        } else if (m.equals("GET") && action.equals("save")) {
            byte[] b = saves.get(id);
            if (b == null) send(ex, 404, "text/plain", "");
            else sendBytes(ex, 200, "application/octet-stream", b);

        } else if (m.equals("PUT") && action.equals("info")) {
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (!isHost(id, player)) {
                send(ex, 403, "text/plain", "");
                return;
            }
            infos.put(id, s);
            dir().child(id + ".info").writeString(s);
            send(ex, 200, "text/plain", "");

        } else if (m.equals("GET") && action.equals("info")) {
            String s = infos.get(id);
            if (s == null) send(ex, 404, "text/plain", "");
            else json(ex, 200, s);
        } else if (m.equals("PUT") && action.equals("items")) {
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (!isHost(id, player)) {
                send(ex, 403, "text/plain", "");
                return;
            }
            Map<String, Integer> map = new ConcurrentHashMap<>();
            for (JsonValue c = new JsonReader().parse(s).child; c != null; c = c.next) map.put(c.name, c.asInt());
            stock.put(id, map);
            saveEconomy();
            send(ex, 200, "text/plain", "");

        } else if (m.equals("GET") && action.equals("pending")) {
            if (!isHost(id, player)) {
                send(ex, 403, "text/plain", "");
                return;
            }
            StringBuilder sb = new StringBuilder("[");
            for (Op o : pending.getOrDefault(id, NONE)) {
                if (sb.length() > 1) sb.append(',');
                sb.append("{\"id\":").append(q(o.id)).append(",\"item\":").append(q(o.item)).append(",\"amount\":").append(o.amount).append('}');
            }
            json(ex, 200, sb.append(']').toString());

        } else if (m.equals("POST") && action.equals("ack")) {
            if (!isHost(id, player)) {
                send(ex, 403, "text/plain", "");
                return;
            }
            Set<String> ids = new HashSet<>(Arrays.asList(query(ex.getRequestURI(), "ids").split(",")));
            List<Op> list = pending.get(id);
            if (list != null && list.removeIf(o -> ids.contains(o.id))) saveEconomy();
            send(ex, 200, "text/plain", "");
        } else {
            send(ex, 404, "text/plain", "");
        }
    }

    // ---------- утиліти ----------

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
    /** вже оброблені списання (id -> час), щоб повтор запиту не списав двічі; старші за годину видаляються */
    static final ConcurrentHashMap<String, Long> doneOps = new ConcurrentHashMap<>();

    /**
     * Запас сектора мінус ще не застосовані списання.
     */
    static int shown(String sector, String item) {
        Map<String, Integer> m = stock.get(sector);
        if (m == null) return 0;
        int v = m.getOrDefault(item, 0);
        for (Op o : pending.getOrDefault(sector, NONE)) if (o.item.equals(item)) v -= o.amount;
        return Math.max(v, 0);
    }

    static synchronized void spend(HttpExchange ex) throws IOException {
        JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
        String opId = j.getString("id");
        long nowMs = System.currentTimeMillis();
        doneOps.values().removeIf(t -> nowMs - t > 3_600_000L);
        if (doneOps.putIfAbsent(opId, nowMs) != null) {
            send(ex, 200, "text/plain", "");
            return;
        }   // повтор запиту

        Map<String, Integer> need = new HashMap<>();
        for (JsonValue c = j.get("items").child; c != null; c = c.next) {
            if (c.asInt() > 0) need.put(c.name, c.asInt());
        }

        for (Map.Entry<String, Integer> e : need.entrySet()) {       // чи вистачає
            int total = 0;
            for (String sec : stock.keySet()) total += shown(sec, e.getKey());
            if (total < e.getValue()) {
                doneOps.remove(opId);
                send(ex, 409, "text/plain", "");
                return;
            }
        }

        for (Map.Entry<String, Integer> e : need.entrySet()) {       // розкладаємо пропорційно, як у ванілі
            String item = e.getKey();
            int left = e.getValue(), total = 0;
            Map<String, Integer> avail = new HashMap<>();
            for (String sec : stock.keySet()) {
                int a = shown(sec, item);
                avail.put(sec, a);
                total += a;
            }
            for (Map.Entry<String, Integer> s : avail.entrySet()) {
                if (left == 0 || s.getValue() == 0) continue;
                int take = Math.min((int) Math.ceil((double) e.getValue() * s.getValue() / total), Math.min(left, s.getValue()));
                if (take <= 0) continue;
                pending.computeIfAbsent(s.getKey(), k -> new CopyOnWriteArrayList<>()).add(new Op(opId + ":" + item, item, take));
                left -= take;
            }
        }
        saveEconomy();
        send(ex, 200, "text/plain", "");
    }

    static boolean isHost(String id, String player) {
        Host h = hosts.get(id);
        return h != null && h.player.equals(player);
    }

    /**
     * id йде в назву файла, тому тільки безпечні символи.
     */
    static boolean safe(String id) {
        return id.matches("[A-Za-z0-9_\\-]{1,64}");
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

    /** Максимум тіла запиту: сейв сектора буває кілька МБ, решта - кілобайти. */
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

    /** Чи це назва справжнього UnlockableContent (щоб у unlocked.txt не потрапляло сміття). */
    static boolean isContent(String name) {
        Set<String> set = contentNames;
        if (set == null) {
            Set<String> s = ConcurrentHashMap.newKeySet();
            mindustry.Vars.content.each(c -> {
                if (c instanceof mindustry.ctype.UnlockableContent u) s.add(u.name);
            });
            contentNames = set = s;
        }
        return set.contains(name);
    }

    static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static void json(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, "application/json", body);
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        sendBytes(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
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