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

import static mindustry.AuthorityAccess.MAX_ASSEMBLING;
import static mindustry.AuthorityAccess.MAX_LINKS;
import static mindustry.AuthorityAccess.MAX_LINKS_PER_IP;
import static mindustry.AuthorityAccess.allowRate;
import static mindustry.AuthorityAccess.assembling;
import static mindustry.AuthorityAccess.buckets;
import static mindustry.AuthorityAccess.connIp;
import static mindustry.AuthorityAccess.ipOf;
import static mindustry.AuthorityAccess.isLoopback;
import static mindustry.AuthorityAccess.linksPerIp;
import static mindustry.AuthorityAccess.ownIps;
import static mindustry.AuthorityAccess.totalLinks;
import static mindustry.AuthorityBans.KICK_MS;
import static mindustry.AuthorityBans.bannedIps;
import static mindustry.AuthorityBans.bans;
import static mindustry.AuthorityBans.kickedUntil;
import static mindustry.AuthorityBans.loadBans;
import static mindustry.AuthorityBans.ownerUuid;
import static mindustry.AuthorityBans.saveBans;
import static mindustry.AuthorityChat.chat;
import static mindustry.AuthorityChat.chatBuckets;
import static mindustry.AuthorityChat.chatLock;
import static mindustry.AuthorityClaims.Host;
import static mindustry.AuthorityClaims.dropHost;
import static mindustry.AuthorityClaims.hosts;
import static mindustry.AuthorityClaims.infoBits;
import static mindustry.AuthorityClaims.infos;
import static mindustry.AuthorityClaims.putInfo;
import static mindustry.AuthorityClaims.saves;
import static mindustry.AuthorityClaims.sectorPlayers;
import static mindustry.AuthorityClaims.shortId;
import static mindustry.AuthorityEconomy.PRODUCE_SECONDS;
import static mindustry.AuthorityEconomy.Waiter;
import static mindustry.AuthorityEconomy.expireWaiters;
import static mindustry.AuthorityEconomy.flushProduction;
import static mindustry.AuthorityEconomy.loadEconomy;
import static mindustry.AuthorityEconomy.pending;
import static mindustry.AuthorityEconomy.produce;
import static mindustry.AuthorityEconomy.saveEconomy;
import static mindustry.AuthorityEconomy.stock;
import static mindustry.AuthorityEconomy.waiters;
import static mindustry.AuthorityResearch.unlocked;
import static mindustry.AuthorityRoutes.MAX_BODY;
import static mindustry.AuthorityRoutes.sector;
import static mindustry.AuthorityRoutes.serve;

/**
 * Built-in Authority: the "brain" of a shared Aurora planet, an ArcNet (TCP) server inside the planet host's game.
 * A request is method, path, token, protocol and body; the answer is a code and a body (see AuroraWire).
 * Keeps who hosts which sector, sector saves, shared research and the shared item stock.
 * — Вбудований Authority: "мозок" спільної планети Aurora, сервер ArcNet (TCP) у грі хоста планети.
 * Тримає, хто хостить який сектор, сейви секторів, спільні дослідження і спільний склад.
 *
 * This class is the public face of the Authority (what the game may call); the internals live in the Authority* classes.
 * — Цей клас — публічне обличчя Authority (те, що може викликати гра); нутрощі живуть у класах Authority*.
 */
public class AuroraAuthority {

    // ---------- What Authority needs from the game — Що Authority потрібно від гри ----------

    /**
     * Everything Authority asks of the game around it. Authority knows nothing about the client (AuroraNet, the player,
     * campaign slots): the game hands this in at start(). Methods are called on every use, from Authority threads.
     * — Усе, що Authority питає в гри навколо. Authority нічого не знає про клієнт (AuroraNet, гравця, слоти кампанії):
     * гра передає це в start(). Методи викликаються щоразу, з потоків Authority.
     */
    public interface Env {
        /** Password of our planet, "" = none — Пароль нашої планети, "" = без пароля. */
        String hostToken();

        /** Secret only this PC knows: marks the planet host's own game — Секрет, який знає лише цей ПК: позначає власну гру хоста планети. */
        String ownerSecret();

        /** The planet host's uuid — uuid хоста планети. */
        String ownerUuid();

        /** Is the planet host playing Aurora right now — Чи хост планети зараз в Aurora. */
        boolean planetActive();

        /** Answer LAN discovery now — Відповідати зараз на пошук у LAN. */
        boolean advertise();

        /** Name shown in "Local servers" — Ім'я в "Локальних серверах". */
        String hostName();

        /** Description shown in "Local servers" — Опис у "Локальних серверах". */
        String discoveryDesc();

        /** Game port shown to LAN discovery — Порт гри для пошуку в LAN. */
        int gamePort();
    }

    /** Set by start(); kept after stop() so requests still finishing can read it — Ставить start(); лишається після stop(), щоб запити, що ще завершуються, могли його читати. */
    static volatile Env env;

    // ---------- Host lifecycle — Життєвий цикл хоста ----------

    static Server server;

    static int boundPort = -1;

    /** Request handlers run here, never on ArcNet's update thread — Обробники запитів працюють тут, ніколи не в потоці оновлення ArcNet. */
    static ThreadPoolExecutor pool;

    static ScheduledExecutorService sweeper;

    public static boolean running() {
        return server != null;
    }

    public static int port() {
        return server == null ? -1 : boundPort;
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
    public static synchronized boolean start(Env environment) {
        if (server != null) return true;
        env = environment;
        try {
            int port = Core.settings.getInt("aurora-port", 8443);
            dir().mkdirs(); // the folder must exist before the first write — папка має існувати до першого запису
            load();
            ownerUuid = env.ownerUuid();

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
                Env en = env;
                if (!en.advertise() || !allowed(address)) return;
                java.nio.ByteBuffer buf = mindustry.net.NetworkIO.writeServerData(
                    en.hostName(), "Aurora", liveCount(), 0,
                    mindustry.game.Gamemode.survival.ordinal(), 0, en.discoveryDesc(), "Aurora", en.gamePort());
                try {
                    handler.respond(buf);
                } catch (NullPointerException | java.nio.channels.ClosedChannelException ignored) {
                    // the server is closing: no answer — сервер закривається: без відповіді
                }
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
            sw.scheduleAtFixedRate(AuthorityEconomy::expireWaiters, 1, 1, TimeUnit.SECONDS);
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
        hosts.clear();
        linksPerIp.clear();
        connIp.clear();
        buckets.clear();
        chatBuckets.clear();
        synchronized (chatLock) {
            chat.clear();
        }
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

    // ---------- Planet bans — Блокування на планеті ----------

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

    /** Bans a player on the whole planet: uuid + IP — Блокує гравця на всій планеті: uuid + IP. */
    public static void ban(String uuid, String ip, String name, String why) {
        uuid = AuroraUtil.planetUuid(uuid); // a sector's con.uuid or the bare one — con.uuid сектора або голий
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
        uuid = AuroraUtil.planetUuid(uuid);
        String[] v = bans.remove(uuid);
        if (v == null) return;
        bannedIps.clear();
        bans.values().forEach(b -> {
            if (!b[0].isEmpty()) bannedIps.add(b[0]);
        });
        saveBans();
        Log.info("Aurora: unbanned @ (@)", v[1], uuid);
    }

    /** Kick from the planet: out of his sector (hosts too), no re-entry for a minute — Видалити з планети: геть із сектора (хостів теж), без повернення хвилину. */
    public static void kick(String uuid) {
        uuid = AuroraUtil.planetUuid(uuid);
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
        return uuid != null && AuroraUtil.planetUuid(uuid).equals(ownerUuid);
    }

    /** For the host dialog: uuid -> {ip, name} — Для вікна хоста: uuid -> {ip, ім'я}. */
    public static Map<String, String[]> banList() {
        return new TreeMap<>(bans);
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

    // ---------- Utils — Утиліти ----------

    /**
     * Write to a temp file, then replace the target in one move: a crash leaves the old file or the new one, never half of one.
     * — Запис у тимчасовий файл, потім заміна одним переміщенням: падіння лишає старий файл або новий, але ніколи не половину.
     */
    static void writeAtomic(Fi target, byte[] data) throws IOException {
        java.nio.file.Path to = target.file().toPath();
        java.nio.file.Path tmp = to.resolveSibling(target.name() + "." + Thread.currentThread().getId() + "." + System.nanoTime() + ".tmp");
        // Files.write does not create folders (Fi.write did): on a fresh install aurora-authority/ did not exist,
        // so nothing was ever saved — no sector saves, research or stock
        // — Files.write не створює папок (Fi.write створював): на свіжому встановленні aurora-authority/ не було,
        // тож нічого не зберігалось — ні сейви секторів, ні дослідження, ні склад
        if (to.getParent() != null) java.nio.file.Files.createDirectories(to.getParent());
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

    // ---------- Access checks for outside callers (see AuthorityAccess) — Перевірки доступу для зовнішніх викликів (див. AuthorityAccess) ----------

    /** Allow a request from this address? See AuthorityAccess — Чи пускати запит з цієї адреси? Див. AuthorityAccess. */
    static boolean allowed(InetAddress a) {
        return AuthorityAccess.allowed(a);
    }

    /** Private network address? See AuthorityAccess — Адреса приватної мережі? Див. AuthorityAccess. */
    static boolean isLan(InetAddress a) {
        return AuthorityAccess.isLan(a);
    }

    /** Tailscale address? See AuthorityAccess — Адреса Tailscale? Див. AuthorityAccess. */
    static boolean isTailscale(InetAddress a) {
        return AuthorityAccess.isTailscale(a);
    }
}
