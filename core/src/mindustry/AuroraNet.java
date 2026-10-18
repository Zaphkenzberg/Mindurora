package mindustry;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.math.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.g3d.*;
import mindustry.type.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static mindustry.Vars.*;

/**
 * Aurora client: talks to the planet's Authority — sector claim, heartbeat, save upload/download,
 * shared research and items, planet map state.
 * — Клієнт Aurora: розмова з Authority планети — клейм сектора, heartbeat, заливання/завантаження сейвів,
 * спільні дослідження й ресурси, стан карти планети.
 */
public class AuroraNet {

    // ---------- Protocol version — Версія протоколу ----------

    /**
     * Version of the game <-> Authority protocol. BUMP by 1 whenever request/response formats change
     * or a required endpoint is added. Different versions refuse to talk: players see "update the build".
     * — Версія протоколу гра <-> Authority. ЗБІЛЬШУЙ на 1, коли міняєш формат запитів/відповідей
     * або додаєш обов'язковий ендпоінт. Різні версії не розмовляють: гравці бачать "оновіть білд".
     */
    public static final int PROTOCOL = 2;

    static long lastProtoMsg;
    /** Don't pop up the version dialog for this thread's requests (server list probe) — Не показувати вікно версії для запитів цього потоку (перевірка списку серверів). */
    static final ThreadLocal<Boolean> quietProto = ThreadLocal.withInitial(() -> false);

    /** Tell the player versions differ (at most once per 30 s) — Сказати гравцю, що версії різні (не частіше разу на 30 с). */
    static void protoMismatch(int theirs) {
        Core.app.post(() -> {
            if (Time.timeSinceMillis(lastProtoMsg) < 30000) return;
            lastProtoMsg = Time.millis();
            ui.loadfrag.hide();
            ui.showErrorMessage("Aurora: різні версії гри.\nУ тебе протокол " + PROTOCOL + ", у хоста планети " +
                (theirs <= 0 ? "старіший (без перевірки версії)" : String.valueOf(theirs)) + ".\nОновіть AURORA до однакового білда.");
        });
    }

    /** -1 unreachable, 0 old Authority without /version, else its protocol — -1 недоступний, 0 старий без /version, інакше його версія. */
    static int remoteProtocol(String base) {
        try {
            Res r = request(base, "GET", "/version", null, null, 2000);
            if (r.code == 404) return 0;
            if (r.code != 200) return -1;
            return parse(r).getInt("proto", 0);
        } catch (Throwable e) {
            return -1;
        }
    }

    // ---------- Who we are — Хто ми ----------

    /** Our own Authority is running — Наш власний Authority запущений. */
    public static volatile boolean localAuthority;
    /** Last known: Authority answers — Останнє відоме: Authority відповідає. */
    public static volatile boolean authorityOk;
    /** We host the current sector / we are a client in someone's sector — Ми хост поточного сектора / клієнт у чужому. */
    public static boolean hosting, asClient;
    /** Guest on a friend's planet (planet map open, not in a sector yet) — Гість на планеті друга (карта відкрита, ще не в секторі). */
    static boolean guestSession;
    /** Host on the next WorldLoadEvent / client already got the world / init done / our server is up
     * — Хостити на наступному WorldLoadEvent / клієнт уже отримав світ / init виконано / наш сервер піднятий. */
    static boolean wantHost, connectedOnce, inited, hostLive;
    static Sector current;
    static long enterTime, clientEnd, lastGoneMsg;
    static int watchFails;
    static volatile boolean shutdownDone;

    /**
     * Authority URL: our own (127.0.0.1) on our own planet, the saved planet host URL as a guest.
     * Our Authority never overwrites "aurora-authority", so a friend's URL is kept.
     * — Адреса Authority: на своїй планеті — свій (127.0.0.1), у гостя — збережена адреса хоста планети.
     * Свій Authority не перезаписує "aurora-authority", тож адреса друга не губиться.
     */
    static String authority() {
        if (planetHost()) return "http://127.0.0.1:" + AuroraAuthority.port();
        return Core.settings.getString("aurora-authority", "http://localhost:8443");
    }

    static String authorityHost() {
        try {
            return new URL(authority()).getHost();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** We are the planet host: own Authority running and we are on our own planet — Ми хост планети: свій Authority і ми на своїй планеті. */
    public static boolean planetHost() {
        return localAuthority && campaignSlot == slotAurora && AuroraAuthority.port() > 0;
    }

    /** Any Aurora session (own planet, guest, sector host or client) — Будь-яка сесія Aurora (своя планета, гість, хост чи клієнт сектора). */
    static boolean sessionActive() {
        return auroraSlot() && (localAuthority || guestSession || hosting || asClient);
    }

    /** Items and research go through Authority (else vanilla) — Ресурси й дослідження йдуть через Authority (інакше ваніла). */
    public static boolean itemsActive() {
        return sessionActive();
    }

    /** Recently a client in an Aurora sector (5 s grace for late disconnect packets) — Нещодавно клієнт Aurora-сектора (5 с запасу на пізні пакети відключення). */
    public static boolean auroraClient() {
        return auroraSlot() && (asClient || Time.timeSinceMillis(clientEnd) < 5000);
    }

    /** IP friends use to join our sector: setting, else Tailscale (100.x), else first LAN IPv4
     * — IP, за яким друзі заходять у наш сектор: налаштування, інакше Tailscale (100.x), інакше перша IPv4 мережі. */
    public static String myIp() {
        String s = Core.settings.getString("aurora-ip", "");
        if (!s.isEmpty() && !s.equals("127.0.0.1")) return s;
        String fallback = null;
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("100.")) return ip; // Tailscale
                    if (fallback == null && !ip.startsWith("192.168.56.")) fallback = ip; // not VirtualBox — не VirtualBox
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "127.0.0.1";
    }

    public static int myPort() {
        return Core.settings.getInt("port", port);
    }

    /** Player UUID: our "key" to the sectors we host — UUID гравця: наш "ключ" до секторів, які ми хостимо. */
    static String me() {
        return platform.getUUID();
    }

    static String myName() {
        return player == null ? "" : Strings.stripColors(player.name);
    }

    // ---------- HTTP ----------

    static class Res {
        int code;
        byte[] body;
    }

    static JsonValue parse(Res r) {
        return new JsonReader().parse(new String(r.body, StandardCharsets.UTF_8));
    }

    /** JSON string with escaping — Рядок JSON з екрануванням. */
    static String js(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** URL-encode a query value — Закодувати значення для URL. */
    static String q(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Sector id used by Authority: "planet-id" — id сектора для Authority: "планета-номер". */
    static String sid(Sector s) {
        return s.planet.name + "-" + s.id;
    }

    /** "?player=<uuid>" for host-only requests — "?player=<uuid>" для запитів лише хоста. */
    static String asMe() {
        return "?player=" + q(me());
    }

    static String sectorPath(Sector s, String action) {
        return "/sector/" + sid(s) + "/" + action;
    }

    static Res request(String method, String path, String contentType, byte[] data) throws Exception {
        return request(authority(), method, path, contentType, data, 5000);
    }

    /** One retry after 300 ms: Windows sometimes says "Address already in use" — Один повтор через 300 мс: Windows інколи каже "Address already in use". */
    static Res request(String base, String method, String path, String contentType, byte[] data, int connectTimeout) throws Exception {
        try {
            return request0(base, method, path, contentType, data, connectTimeout);
        } catch (SocketException e) {
            Thread.sleep(300);
            return request0(base, method, path, contentType, data, connectTimeout);
        }
    }

    static Res request0(String base, String method, String path, String contentType, byte[] data, int connectTimeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
        con.setConnectTimeout(connectTimeout);
        con.setReadTimeout(15000);
        con.setRequestMethod(method);
        con.setRequestProperty("Connection", "close");
        con.setRequestProperty("X-Aurora-Proto", String.valueOf(PROTOCOL));
        String tok = Core.settings.getString("aurora-token", "");
        if (!tok.isEmpty()) con.setRequestProperty("X-Aurora-Token", tok);
        if (data != null) {
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", contentType);
            con.setFixedLengthStreamingMode(data.length);
            try (OutputStream out = con.getOutputStream()) {
                out.write(data);
            }
        }

        Res res = new Res();
        res.code = con.getResponseCode();
        InputStream in = res.code >= 400 ? con.getErrorStream() : con.getInputStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bytes.write(buf, 0, n);
            in.close();
        }
        res.body = bytes.toByteArray();
        con.disconnect();

        // 426 = Authority speaks another protocol version — 426 = Authority говорить іншою версією протоколу
        if (res.code == 426 && !quietProto.get()) {
            int theirs = 0;
            try {
                theirs = parse(res).getInt("proto", 0);
            } catch (Throwable ignored) {
            }
            protoMismatch(theirs);
        }
        return res;
    }

    static Res post(String path) throws Exception {
        return request("POST", path, "text/plain", new byte[0]);
    }

    static Res get(String path) throws Exception {
        return request("GET", path, null, null);
    }

    static void putBytes(String path, byte[] data) throws Exception {
        Res res = request("PUT", path, "application/octet-stream", data);
        if (res.code != 200) throw new RuntimeException("PUT " + path + " -> " + res.code);
    }

    static byte[] getBytes(String path) throws Exception {
        Res res = get(path);
        return res.code == 200 ? res.body : null;
    }

    // ---------- Sector claim — Клейм сектора ----------

    /** Authority's answer to "I want this sector" — Відповідь Authority на "хочу цей сектор". */
    public static class Claim {
        /** We are the host now — Тепер ми хост. */
        public boolean host;
        /** Sector temporarily closed (host vanished, check / grace time) — Сектор тимчасово закритий (хост зник, перевірка / запас часу). */
        public boolean wait;
        public String state, ip;
        public int retry, port;
    }

    static Claim claim(Sector s) throws Exception {
        String body = "{\"ip\":" + js(myIp()) + ",\"port\":" + myPort() + ",\"player\":" + js(me()) + "}";
        Res res = request("POST", sectorPath(s, "claim"), "application/json", body.getBytes(StandardCharsets.UTF_8));
        if (res.code != 200) throw new RuntimeException("claim -> " + res.code);
        JsonValue v = parse(res);
        Claim c = new Claim();
        String role = v.getString("role");
        c.host = role.equals("host");
        c.wait = role.equals("wait");
        c.state = v.getString("state", "");
        c.retry = v.getInt("retry", 5);
        c.ip = v.getString("ip", null);
        c.port = v.getInt("port", 0);
        return c;
    }

    // ---------- Sector data — Дані сектора ----------

    /** Saves the sector and returns {save, info}, or null if we are not in it — Зберігає сектор і повертає {сейв, info}, або null, якщо ми не в ньому. */
    static byte[][] snapshot(Sector s) {
        try {
            if (!(state.isGame() && state.rules.sector == s)) return null;
            s.info.prepare(s);
            control.saves.saveSector(s);
            return new byte[][]{s.save.file.readBytes(), s.exportInfo().getBytes(StandardCharsets.UTF_8)};
        } catch (Exception e) {
            Log.err("snapshot failed", e);
            return null;
        }
    }

    static void push(Sector s, byte[][] pack) throws Exception {
        putBytes(sectorPath(s, "save") + asMe(), pack[0]);
        putBytes(sectorPath(s, "info") + asMe(), pack[1]);
    }

    // ---------- Entering a sector — Вхід у сектор ----------

    /** Another sector picked on the map: a host leaves cleanly, a client disconnects quietly, then both enter
     * — Вибрано інший сектор на карті: хост чисто виходить, клієнт тихо відключається, потім обидва заходять. */
    public static void switchTo(Sector sector) {
        if (sector == current && (hosting || asClient)) return;
        if (hosting) {
            leave(() -> enter(sector));
            return;
        }
        if (asClient) {
            endClient();
            netClient.disconnectQuietly();
            logic.reset();
        }
        enter(sector);
    }

    /** Become the host, or join the existing host — Стати хостом або підключитись до наявного хоста. */
    public static void enter(Sector sector) {
        enter(sector, false);
    }

    static void enter(Sector sector, boolean retried) {
        syncUnlocks();
        ui.loadfrag.show("Aurora...");
        mainExecutor.submit(() -> {
            try {
                Claim c = claim(sector);

                if (c.wait) {
                    // Host vanished: wait for the check (30 s) and grace time (15 s), then retry — Хост зник: чекаємо перевірку (30 с) і запас (15 с), потім знову
                    Core.app.post(() -> waitForSector(sector, c));
                } else if (c.host) {
                    byte[] save = getBytes(sectorPath(sector, "save"));
                    byte[] info = getBytes(sectorPath(sector, "info"));
                    Core.app.post(() -> startHosting(sector, save, info));
                } else {
                    // Host on the planet host's PC reports loopback: use the Authority address instead — Хост на ПК хоста планети дає loopback: беремо адресу Authority
                    String ip = (c.ip == null || c.ip.equals("127.0.0.1") || c.ip.equals("localhost")) ? authorityHost() : c.ip;
                    if (!retried && !portOpen(ip, c.port, 12)) {
                        Core.app.post(() -> enter(sector, true));
                        return;
                    }
                    Core.app.post(() -> {
                        current = sector;
                        asClient = true;
                        connectedOnce = false;
                        enterTime = Time.millis();
                        ui.loadfrag.hide();
                        ui.join.connectRaw(ip, c.port);
                    });
                }
            } catch (Throwable e) {
                Log.err("AuroraNet.enter failed", e);
                Core.app.post(() -> {
                    ui.loadfrag.hide();
                    ui.showException("Authority недоступний", e);
                });
            }
        });
    }

    static boolean waitCancelled;

    /** Sector locked: show a countdown and retry every ≤3 s, with a cancel button — Сектор закритий: відлік і повтор кожні ≤3 с, з кнопкою скасування. */
    static void waitForSector(Sector sector, Claim c) {
        if (waitCancelled) {
            waitCancelled = false;
            ui.loadfrag.hide();
            return;
        }
        String what = "lost".equals(c.state) ? "Хост сектора не відповідає, перевіряємо" : "Сектор оновлюється";
        ui.loadfrag.show("[accent]Aurora:[] " + what + "...\nВідкриється приблизно через " + c.retry + " с");
        ui.loadfrag.setButton(() -> {
            waitCancelled = true;
            ui.loadfrag.hide();
        });
        Time.runTask(Math.min(c.retry, 3) * 60f, () -> {
            if (waitCancelled) {
                waitCancelled = false;
                return;
            }
            enter(sector);
        });
    }

    /** Waits up to secs seconds until the host listens on the port — Чекає до secs секунд, поки хост почне слухати порт. */
    static boolean portOpen(String ip, int port, int secs) {
        for (int i = 0; i < secs; i++) {
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(ip, port), 1000);
                return true;
            } catch (Exception e) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    return false;
                }
            }
        }
        return false;
    }

    /** We became the host: restore the sector from Authority and load it; hosting starts on WorldLoadEvent
     * — Ми стали хостом: відновлюємо сектор з Authority і вантажимо; хостинг стартує на WorldLoadEvent. */
    static void startHosting(Sector sector, byte[] save, byte[] info) {
        try {
            if (save != null) {
                control.saves.getSectorFile(sector).writeBytes(save);
                sector.save = null;
                control.saves.load();
            }
            if (info != null) sector.importInfo(new String(info, StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.err("restore sector data failed", e);
        }
        current = sector;
        hosting = true;
        wantHost = true;
        ui.loadfrag.hide();
        control.playSector(sector);
    }

    /** Server self-check (UDP ping to ourselves) — Самоперевірка сервера (UDP-пінг самого себе). */
    static void selfCheck(String tag, boolean quietOk) {
        net.pingHost("127.0.0.1", myPort(),
            h -> {
                if (!quietOk) ui.showInfoToast("[accent]Aurora " + tag + ": сервер живий", 3f);
            },
            e -> ui.showInfoToast("[scarlet]Aurora " + tag + ": сервер НЕ відповідає", 8f));
    }

    // ---------- Heartbeat ----------

    static Timer.Task beat;
    static int beats, beatFails;
    /** Upload the save on the next beat (after a failure) — Залити сейв на наступному beat (після помилки). */
    static boolean forcePush;
    /** Every N beats the save is uploaded anyway (18 x 10 s = 3 min) — Кожні N beat сейв заливається в будь-якому разі (18 x 10 с = 3 хв). */
    static final int PUSH_EVERY_BEATS = 18;

    /**
     * Every 10 s while hosting: apply queued spends, heartbeat, push stock, sometimes the save.
     * After a failure the next beat claims the sector again.
     * — Кожні 10 с, поки хостимо: застосувати списання, heartbeat, склад, інколи сейв.
     * Після помилки наступний beat заклеймить сектор знову.
     */
    static void startBeat() {
        stopBeat();
        beats = 0;
        beatFails = 0;
        forcePush = false;
        beat = Timer.schedule(() -> Core.app.post(() -> {
            Sector s = current;
            if (!hosting || s == null) return;
            syncStock(s);
            boolean up = ++beats % PUSH_EVERY_BEATS == 0 || forcePush;
            forcePush = false;
            if (beats % 3 == 0) selfCheck("beat", true);
            byte[][] pack = up ? snapshot(s) : null;
            if (pack != null) lastPush = Time.millis();
            boolean reclaim = beatFails > 0;
            mainExecutor.submit(() -> {
                boolean ok = false;
                try {
                    if (reclaim) {
                        Claim c = claim(s);
                        if (c.wait) throw new RuntimeException("sector temporarily locked, retry");
                        if (!c.host) {
                            // Someone took the sector while we were offline: their world wins — Поки ми були офлайн, сектор забрали: головний їхній світ
                            Core.app.post(() -> lostSector(s));
                            return;
                        }
                    }
                    // 404: Authority forgot us (restart), next beat claims again — 404: Authority нас забув (рестарт), наступний beat заклеймить знову
                    if (post(sectorPath(s, "heartbeat") + asMe()).code == 404) throw new RuntimeException("heartbeat: not the host anymore");
                    if (pack != null) push(s, pack);
                    ok = true;
                } catch (Exception e) {
                    Log.err("beat failed", e);
                }
                boolean good = ok;
                Core.app.post(() -> onBeat(good));
            });
        }), 10f, 10f);
    }

    static void stopBeat() {
        if (beat != null) {
            beat.cancel();
            beat = null;
        }
    }

    static void onBeat(boolean ok) {
        if (ok) {
            if (beatFails >= 3) ui.showInfoToast("[accent]Aurora: зв'язок з Authority відновлено", 4f);
            beatFails = 0;
        } else {
            forcePush = true;
            if (++beatFails == 3) {
                ui.showInfoToast("[scarlet]Aurora: Authority недоступний.\nПрогрес не зберігається, гра триває.", 8f);
            }
        }
    }

    /**
     * We were pushed out of hosting (someone else hosts the sector now). To avoid two copies of one sector:
     * close our server, upload nothing, join the new host as a client.
     * — Нас витіснили з хостингу (сектор тепер хостить інший). Щоб не було двох копій сектора:
     * закриваємо свій сервер, нічого не заливаємо, підключаємось до нового хоста клієнтом.
     */
    static void lostSector(Sector s) {
        if (!hosting || current != s) return;
        Log.warn("Aurora: sector @ taken by another host, stepping down", sid(s));
        hosting = false;
        hostLive = false;
        wantHost = false;
        stopBeat();
        current = null;
        if (net.server()) net.closeServer();
        logic.reset();
        ui.showInfoToast("[accent]Aurora:[] сектор уже хостить інший гравець.\nПідключаємось до нього, твої зміни з часу обриву зв'язку не збережено.", 7f);
        Time.runTask(60f, () -> {
            if (!hosting && !asClient && auroraSlot()) enter(s);
        });
    }

    // ---------- Event saves — Збереження при подіях ----------

    static long lastPush;
    static final long PUSH_COOLDOWN = 20_000L;

    /**
     * Snapshot and upload right now (not waiting for the 3-minute beat). force = ignore the cooldown (capture, launch).
     * — Знімок і заливання просто зараз (не чекаючи 3-хвилинного beat). force = без паузи між заливками (захоплення, запуск).
     */
    static void pushNow(boolean force, String why) {
        Core.app.post(() -> {
            Sector s = current;
            if (!hosting || !hostLive || s == null) return;
            if (!force && Time.timeSinceMillis(lastPush) < PUSH_COOLDOWN) return;
            byte[][] pack = snapshot(s);
            if (pack == null) return;
            lastPush = Time.millis();
            mainExecutor.submit(() -> {
                try {
                    push(s, pack);
                    Log.info("Aurora: sector @ saved to Authority (@)", sid(s), why);
                } catch (Exception e) {
                    Log.err("event push failed (" + why + ")", e);
                    forcePush = true; // next beat retries — наступний beat спробує ще раз
                }
            });
        });
    }

    /**
     * App is being minimized (Android may kill it): snapshot, upload, wait up to timeoutMs. Called from pause() on the main thread.
     * — Гру згортають (Android може вбити): знімок, заливання, чекаємо до timeoutMs. Викликається з pause() на головному потоці.
     */
    static void pushBlocking(long timeoutMs) {
        Sector s = current;
        if (!hosting || !hostLive || s == null) return;
        byte[][] pack = snapshot(s);
        if (pack == null) return;
        lastPush = Time.millis();
        Thread t = new Thread(() -> {
            try {
                push(s, pack);
                Log.info("Aurora: sector @ saved on pause", sid(s));
            } catch (Exception e) {
                Log.err("pause push failed", e);
            }
        }, "aurora-pause-push");
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ignored) {
        }
    }

    // ---------- Leaving — Вихід ----------

    public static void leave() {
        leave(null);
    }

    /**
     * Clean host exit: save -> upload -> release the sector -> close the server. then (if given) runs instead of returning to the planet.
     * — Чистий вихід хоста: зберегти -> залити -> звільнити сектор -> закрити сервер. then (якщо є) замість повернення на планету.
     */
    public static void leave(Runnable then) {
        Sector s = current;
        if (!hosting || s == null) {
            if (then != null) then.run();
            return;
        }
        hosting = false;
        hostLive = false;
        stopBeat();
        byte[][] pack = snapshot(s);   // world still alive, take a snapshot — світ ще живий, робимо знімок
        net.closeServer();             // free the port BEFORE release — порт звільняємо ДО release
        ui.loadfrag.show("Aurora: зберігаю...");
        mainExecutor.submit(() -> {
            try {
                if (pack != null) push(s, pack);
            } catch (Exception e) {
                Log.err("leave: push failed", e);
            }
            release(s, "leave");
            Core.app.post(() -> {
                ui.loadfrag.hide();
                logic.reset();
                current = null;
                if (then != null) then.run();
                else returnFromSector();
            });
        });
    }

    /** Tell Authority we no longer host the sector (call off the main thread) — Сказати Authority, що ми більше не хостимо сектор (не з головного потоку). */
    static void release(Sector s, String why) {
        try {
            post(sectorPath(s, "release") + asMe());
        } catch (Throwable t) {
            Log.err(why + ": release failed", t);
        }
    }

    /** Once: snapshot -> upload -> release -> stop Authority — Один раз: знімок -> заливання -> release -> зупинка Authority. */
    static synchronized void shutdownWork() {
        if (shutdownDone) return;
        shutdownDone = true;
        Sector s = current;
        if (hosting && s != null) {
            try {
                byte[][] pack = snapshot(s);
                if (pack != null) push(s, pack);
            } catch (Throwable t) {
                Log.err("shutdown: push failed", t);
            }
            release(s, "shutdown");
        }
        if (localAuthority) AuroraAuthority.stop(); // snapshot/release first, then stop — спершу знімок/release, потім stop
    }

    /** Reset "client of a sector" state — Скинути стан "клієнт сектора". */
    static void endClient() {
        asClient = false;
        connectedOnce = false;
        current = null;
        clientEnd = Time.millis();
    }

    /** The host of the sector we were in closed the server. Called from NetClient.kick — Хост сектора, де ми були клієнтом, закрив сервер. Викликається з NetClient.kick. */
    public static void hostLeft() {
        if (!asClient) return;
        endClient();
        ui.showInfoToast("[accent]Aurora:[] хост вийшов із сектора", 4f);
        returnFromSector();
    }

    /** Out of a sector: planet map if Authority is alive, else main menu — Вийшли з сектора: карта планети, якщо Authority живий, інакше головне меню. */
    static void returnFromSector() {
        checkAuthority(() -> {
            if (authorityOk) {
                guestSession = !planetHost();
                ui.planet.show();
            } else {
                guestSession = false;
                if (net.client()) netClient.disconnectQuietly();
                ui.planet.hide();
                showGone("Aurora: Authority недоступний.\nПовертаємось у головне меню.");
            }
        });
    }

    /** "Gone" message at most once per 10 s, so dialogs don't pile up — Повідомлення про зникнення не частіше разу на 10 с, щоб не накопичувались. */
    static void showGone(String msg) {
        if (Time.timeSinceMillis(lastGoneMsg) < 10000) return;
        lastGoneMsg = Time.millis();
        ui.showErrorMessage(msg);
    }

    // ---------- Campaign slots — Слоти кампанії ----------

    /**
     * Wipes what is left from the previous guest session: sector saves (saves-guest/) and "cg-" keys.
     * The real planet data lives in its Authority.
     * — Стирає залишки попередньої гостьової сесії: сейви секторів (saves-guest/) і ключі "cg-".
     * Справжні дані планети живуть у її Authority.
     */
    public static void wipeGuestData() {
        try {
            arc.files.Fi dir = dataDirectory.child(campaignSaveDir(slotGuest));
            if (dir.exists()) for (arc.files.Fi f : dir.list()) f.delete();
            Seq<String> keys = new Seq<>();
            for (String k : Core.settings.keys()) if (k.startsWith("cg-")) keys.add(k);
            for (String k : keys) Core.settings.remove(k);
            Log.info("Aurora: guest data wiped (@ keys)", keys.size);
        } catch (Throwable e) {
            Log.err("wipeGuestData failed", e);
        }
    }

    /** From switchCampaign: leaving to the normal campaign clears the guest state — Із switchCampaign: перехід у звичайну кампанію гасить стан гостя. */
    public static void onCampaignSlot(int slot) {
        if (slot != slotNormal) return;
        guestSession = false;
        watchFails = 0;
        remoteBases = new ObjectSet<>();
        liveSectors = new ObjectSet<>();
        remoteState = new ObjectIntMap<>();
    }

    // ---------- Planet map — Карта планети ----------

    // ---------- Map colors, change them here only — Кольори карт, міняти лише тут ----------

    /** Base frame (anyone's captured sector), menu and campaign map — Рамка бази (захоплений сектор будь-кого), меню й карта кампанії. */
    public static final Color baseColor = new Color(Team.sharded.color);
    /** Ally color (other players' live hosts) on maps — Колір союзників (живі хости інших гравців) на картах. */
    public static final Color allyColor = Color.valueOf("5aa7ff");
    /** Attack on an enemy base — Атака на ворожу базу. */
    public static final Color attackColor = new Color(Team.crux.color);
    /** Shield and cage on the menu background — Щит і клітка на фоні меню. */
    public static final Color shieldColor = new Color(Team.sharded.color), cageColor = new Color(mindustry.graphics.Pal.accent);

    static volatile ObjectSet<String> remoteBases = new ObjectSet<>(), liveSectors = new ObjectSet<>();
    /** Sector state from Authority: 1 core, 2 waves, 4 attack — Стан секторів з Authority: 1 ядро, 2 хвилі, 4 атака. */
    static volatile ObjectIntMap<String> remoteState = new ObjectIntMap<>();

    /** A base someone holds in the shared campaign (per Authority) — База, яку хтось тримає у спільній кампанії (за Authority). */
    public static boolean remoteBase(Sector s) {
        return s != null && sessionActive() && remoteBases.contains(sid(s));
    }

    /** The sector has a live host right now — У секторі зараз є живий хост. */
    public static boolean liveSector(Sector s) {
        return s != null && sessionActive() && liveSectors.contains(sid(s));
    }

    public static boolean remoteWaves(Sector s) {
        return s != null && sessionActive() && (remoteState.get(sid(s), 0) & 2) != 0;
    }

    public static boolean remoteAttack(Sector s) {
        return s != null && sessionActive() && (remoteState.get(sid(s), 0) & 4) != 0;
    }

    /**
     * Marks for live sectors (menu background and campaign map); the normal base frame is drawn before this.
     * Host on a captured base: blue frame. Someone capturing (waves running / no base yet): blinking blue, orange frame stays.
     * Someone on an enemy base (attack): blinking red.
     * — Позначки живих секторів (фон меню й карта кампанії); звичайна рамка бази малюється до цього.
     * Хост на захопленій базі: синя рамка. Хтось захоплює (хвилі / бази ще нема): блимає синім, помаранчева рамка лишається.
     * Хтось на ворожій базі (атака): блимає червоним.
     */
    public static void drawLiveSector(PlanetRenderer planets, Sector sec, boolean base, boolean waves, boolean attack, float alpha) {
        if (!liveSector(sec)) return;
        float pulse = Mathf.absin(Time.time, 7f, 1f);
        Color c = Tmp.c1;
        if (attack || (!base && sec.hasEnemyBase())) {
            Color red = attackColor;
            planets.fill(sec, c.set(red).a(alpha * (0.15f + 0.35f * pulse)), -0.001f);
            planets.drawBorders(sec, red, alpha * (0.5f + 0.5f * pulse));
        } else if (!base || waves) {
            planets.fill(sec, c.set(allyColor).a(alpha * (0.15f + 0.35f * pulse)), -0.001f);
        } else {
            planets.drawSelection(sec, c.set(allyColor).a(alpha), 0.026f, -0.001f);
        }
    }

    /** Pull sector states and live hosts from Authority (every 5 s) — Тягне стан секторів і живих хостів з Authority (кожні 5 с). */
    public static void refreshMap() {
        if (!sessionActive()) return;
        mainExecutor.submit(() -> {
            try {
                ObjectSet<String> bases = new ObjectSet<>(), live = new ObjectSet<>();
                ObjectIntMap<String> st = new ObjectIntMap<>();
                Res cap = get("/sectors/state");
                if (cap.code == 200) {
                    for (JsonValue c = parse(cap).child; c != null; c = c.next) {
                        int bits = c.asInt();
                        st.put(c.name, bits);
                        if ((bits & 1) != 0) bases.add(c.name);
                    }
                }
                Res lv = get("/sectors");
                if (lv.code == 200) {
                    for (JsonValue c = parse(lv).child; c != null; c = c.next) live.add(c.name);
                }
                if (cap.code != 200 && lv.code != 200) return;
                Core.app.post(() -> {
                    remoteBases = bases;
                    liveSectors = live;
                    remoteState = st;
                });
            } catch (Throwable t) {
                Log.err("refreshMap failed", t);
            }
        });
    }

    // ---------- Shared research — Спільні дослідження ----------

    /** True while applying unlocks that came from Authority (don't push them back) — true, поки застосовуємо дослідження з Authority (назад не шлемо). */
    static boolean applyingRemote;

    /**
     * Applies other players' research as real unlocks (with UnlockEvent): build menu and tech tree update at once,
     * a sector host forwards them to its clients (Call.researched), a toast says who researched what.
     * — Застосовує дослідження інших гравців як справжні (з UnlockEvent): меню будівництва й дерево оновлюються одразу,
     * хост сектора пересилає їх своїм клієнтам (Call.researched), сповіщення каже, хто що дослідив.
     */
    static void applyRemoteUnlocks(Seq<UnlockableContent> fresh, ObjectMap<String, String> by) {
        if (fresh.isEmpty()) return;
        applyingRemote = true;
        try {
            if (fresh.size <= 5) {
                for (var u : fresh) {
                    u.unlock();
                    String who = by.get(u.name, "");
                    if (state.isGame() && !who.isEmpty() && !who.equals(myName())) {
                        ui.hudfrag.showToast(new arc.scene.style.TextureRegionDrawable(u.uiIcon), "[accent]" + who + "[] дослідив: " + u.localizedName);
                    }
                }
            } else {
                // Bulk sync (first join): no flood of toasts — Масова синхронізація (перший вхід): без лавини сповіщень
                for (var u : fresh) {
                    u.quietUnlock();
                    if (net.server()) Call.researched(u);
                }
                // One event so the build menu and tech tree rebuild — Одна подія, щоб перебудувались меню будівництва й дерево
                Events.fire(new UnlockEvent(fresh.peek()));
                if (state.isGame()) ui.hudfrag.showToast(Icon.book, "[accent]Aurora:[] отримано досліджень: " + fresh.size);
            }
        } finally {
            applyingRemote = false;
        }
    }

    static void pushUnlock(String name, String by) throws Exception {
        get("/unlock/" + q(name) + "?by=" + q(by));
    }

    /** Pull shared unlocks from Authority and push ours it does not have yet — Тягне спільні дослідження з Authority і заливає наші, яких там ще нема. */
    public static void syncUnlocks() {
        mainExecutor.submit(() -> {
            try {
                Res res = get("/unlocks");
                if (res.code != 200) return;

                // name -> who researched — назва -> хто дослідив
                ObjectMap<String, String> remote = new ObjectMap<>();
                for (JsonValue c = parse(res).child; c != null; c = c.next) remote.put(c.name, c.asString());

                Core.app.post(() -> {
                    if (!auroraSlot()) return;
                    Seq<String> missing = new Seq<>();
                    Seq<UnlockableContent> fresh = new Seq<>();
                    content.each(c -> {
                        if (!(c instanceof UnlockableContent u)) return;
                        if (remote.containsKey(u.name)) {
                            if (!u.unlockedLocal()) fresh.add(u);
                        } else if (u.unlocked() && !u.alwaysUnlocked) {
                            missing.add(u.name);
                        }
                    });
                    applyRemoteUnlocks(fresh, remote);
                    if (missing.isEmpty()) return;
                    String by = myName();
                    mainExecutor.submit(() -> {
                        for (String n : missing) {
                            try {
                                pushUnlock(n, by);
                            } catch (Exception e) {
                                Log.err("unlock push failed", e);
                                break;
                            }
                        }
                    });
                });
            } catch (Throwable t) {
                Log.err("syncUnlocks failed", t);
            }
        });
    }

    // ---------- Shared items — Спільні ресурси ----------

    static volatile ItemSeq shared = new ItemSeq();

    /** Shared stock as last seen from Authority — Спільний склад, яким його востаннє бачив Authority. */
    public static ItemSeq globalItems() {
        return shared.copy();
    }

    public static void refreshItems() {
        if (!itemsActive()) return;
        mainExecutor.submit(() -> {
            try {
                Res res = get("/items");
                if (res.code != 200) return;
                ItemSeq seq = new ItemSeq();
                for (JsonValue c = parse(res).child; c != null; c = c.next) {
                    Item it = content.item(c.name);
                    if (it != null) seq.set(it, c.asInt());
                }
                Core.app.post(() -> {
                    shared = seq;
                    if (ui.research.isShown()) ui.research.rebuildItems();
                });
            } catch (Throwable t) {
                Log.err("refreshItems failed", t);
            }
        });
    }

    /**
     * Confirmed spend: done(true) only if Authority accepted it (200). 409 = not enough, any error = no connection.
     * done runs on the main thread.
     * — Списання з підтвердженням: done(true) лише якщо Authority прийняв (200). 409 = не вистачає, помилка = нема зв'язку.
     * done виконується на головному потоці.
     */
    public static void spendConfirmed(ObjectIntMap<Item> need, Boolc done) {
        StringBuilder items = new StringBuilder("{");
        for (var e : need) {
            if (items.length() > 1) items.append(',');
            items.append(js(e.key.name)).append(':').append(e.value);
        }
        String body = "{\"id\":" + js(java.util.UUID.randomUUID().toString()) + ",\"items\":" + items.append('}') + "}";
        mainExecutor.submit(() -> {
            boolean ok = false;
            try {
                Res res = request("POST", "/spend", "application/json", body.getBytes(StandardCharsets.UTF_8));
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
    static void syncStock(Sector s) {
        mainExecutor.submit(() -> {
            try {
                Res r = get(sectorPath(s, "pending") + asMe());
                JsonValue arr = r.code == 200 ? parse(r) : null;
                Core.app.post(() -> {
                    if (!hosting || current != s || !state.isGame() || state.rules.sector != s) return;
                    var core = state.rules.defaultTeam.core();
                    StringBuilder acked = new StringBuilder();
                    if (arr != null) {
                        for (JsonValue c = arr.child; c != null; c = c.next) {
                            Item it = content.item(c.getString("item"));
                            if (it != null && core != null) core.items.remove(it, Math.min(c.getInt("amount"), core.items.get(it)));
                            if (acked.length() > 0) acked.append(',');
                            acked.append(c.getString("id"));
                        }
                    }
                    String body = stockJson();
                    mainExecutor.submit(() -> {
                        try {
                            putBytes(sectorPath(s, "items") + asMe(), body.getBytes(StandardCharsets.UTF_8));
                            if (acked.length() > 0) post(sectorPath(s, "ack") + asMe() + "&ids=" + q(acked.toString()));
                        } catch (Exception e) {
                            Log.err("syncStock push failed", e);
                        }
                    });
                });
            } catch (Throwable t) {
                Log.err("syncStock failed", t);
            }
        });
    }

    // ---------- Authority watch — Сторож Authority ----------

    /** Does Authority answer? (off the main thread) — Чи відповідає Authority? (не з головного потоку) */
    static boolean pingAuthority(int timeout) {
        try {
            return request(authority(), "GET", "/sectors", null, null, timeout).code == 200;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Started ONCE from init(). While we are a guest or a sector host/client, checks the planet host is alive; 3 misses = gone.
     * — Запускається ОДИН раз з init(). Поки ми гість чи хост/клієнт сектора, перевіряє, чи живий хост планети; 3 промахи = зник.
     */
    static void startWatch() {
        Timer.schedule(() -> Core.app.post(() -> {
            if (planetHost()) {
                watchFails = 0;
                return;
            }
            boolean active = hosting || asClient || (guestSession && ui.planet.isShown());
            if (!active) {
                guestSession = false;
                watchFails = 0;
                return;
            }
            mainExecutor.submit(() -> {
                boolean good = pingAuthority(3000);
                Core.app.post(() -> {
                    authorityOk = good;
                    if (good) watchFails = 0;
                    else if (++watchFails >= 3) authorityGone();
                });
            });
        }), 5f, 5f);
    }

    /** The planet host vanished: save what we can, leave everything, back to the main menu — Хост планети зник: зберегти, що можна, вийти звідусіль, у головне меню. */
    static void authorityGone() {
        if (planetHost()) return;
        Sector s = current;
        boolean wasClient = asClient;
        watchFails = 0;
        guestSession = false;
        if (hosting && s != null) {
            snapshot(s);
            hosting = false;
            hostLive = false;
            stopBeat();
            net.closeServer();
        }
        endClient();
        if (wasClient) netClient.disconnectQuietly();
        logic.reset();
        ui.planet.hide();
        showGone("Aurora: господар планети зник.\nПовертаємось у головне меню.");
    }

    /** Quiet check: onChange runs on the main thread only if the state changed — Тиха перевірка: onChange лише якщо стан змінився. */
    public static void watchAuthority(Runnable onChange) {
        mainExecutor.submit(() -> {
            boolean was = authorityOk, ok = pingAuthority(5000);
            authorityOk = ok;
            if (ok != was) Core.app.post(onChange);
        });
    }

    /** Check Authority, then run done on the main thread — Перевірити Authority, потім done на головному потоці. */
    public static void checkAuthority(Runnable done) {
        mainExecutor.submit(() -> {
            authorityOk = pingAuthority(5000);
            Core.app.post(done);
        });
    }

    // ---------- Join Game ----------

    /**
     * For the server list: is there an Aurora planet at this IP even though no game server answers (host still in the menu)?
     * done gets a label text, or null if not Aurora. Runs on the main thread.
     * — Для списку серверів: чи є за цим IP планета Aurora, хоча сервер гри не відповідає (хост ще в меню)?
     * done отримує текст підпису, або null, якщо не Aurora. На головному потоці.
     */
    public static void probePlanet(String ip, Cons<String> done) {
        String host = ip.indexOf(':') >= 0 && !ip.startsWith("[") ? "[" + ip + "]" : ip;
        String base = "http://" + host + ":8443";
        mainExecutor.submit(() -> {
            String text = null;
            quietProto.set(true);
            try {
                Res mode = request(base, "GET", "/mode", null, null, 2000);
                int theirs = mode.code == 426 ? remoteProtocol(base) : PROTOCOL;
                if (mode.code == 401) {
                    text = "[accent]Aurora[] — [scarlet]потрібен пароль[] (налаштування Aurora)";
                } else if (mode.code == 426) {
                    text = "[accent]Aurora[] — [scarlet]інша версія гри (" + (theirs > 0 ? theirs : "стара") + ", у тебе " + PROTOCOL + ")";
                } else {
                    if (mode.code == 200 && parse(mode).getBoolean("active", false)) {
                        int live = 0;
                        Res s = request(base, "GET", "/sectors", null, null, 2000);
                        if (s.code == 200) for (JsonValue c = parse(s).child; c != null; c = c.next) live++;
                        text = "[accent]Aurora[] — планета відкрита\n[lightgray]" + (live == 0 ? "у секторах ще ніхто не грає" : "грають у секторах: " + live) + "\nнатисни, щоб зайти";
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                quietProto.set(false);
            }
            String result = text;
            Core.app.post(() -> done.get(result));
        });
    }

    /**
     * Join Game: if this address (or the saved Authority) is an Aurora planet, enter Aurora as a guest.
     * The Authority lives as long as the host's game runs, even in the normal campaign, so Aurora only if
     * the host is in Aurora now, or this ip:port is a live Aurora sector.
     * — Приєднатись до гри: якщо за цією адресою (або за збереженим Authority) планета Aurora, заходимо гостем.
     * Authority живе, поки гра хоста запущена, навіть у звичайній кампанії, тож Aurora лише якщо
     * хост зараз в Aurora, або цей ip:port — живий Aurora-сектор.
     */
    public static void probeAndJoin(String ip, int port, Boolc done) {
        String host = ip.indexOf(':') >= 0 && !ip.startsWith("[") ? "[" + ip + "]" : ip;
        String direct = "http://" + host + ":8443";
        mainExecutor.submit(() -> {
            String found = null;

            try {
                Res mode = request(direct, "GET", "/mode", null, null, 2000);
                if (mode.code == 200 && parse(mode).getBoolean("active", false)) {
                    found = direct;
                } else if (mode.code == 200 || mode.code == 404) {
                    Res res = request(direct, "GET", "/sectors", null, null, 2000);
                    if (res.code == 200) {
                        for (JsonValue c = parse(res).child; c != null; c = c.next) {
                            if (c.getInt("port", 0) == port) {
                                found = direct;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // The address may be a sector host, not the planet host: ask the saved Authority — Адреса може бути хостом сектора, а не планети: питаємо збережений Authority
            if (found == null) {
                String saved = authority();
                try {
                    if (!saved.equals(direct)) {
                        Res res = request(saved, "GET", "/sectors", null, null, 2000);
                        if (res.code == 200) {
                            String authHost = new URL(saved).getHost();
                            for (JsonValue c = parse(res).child; c != null; c = c.next) {
                                String hip = c.getString("ip", "");
                                boolean loop = hip.equals("127.0.0.1") || hip.equals("localhost");
                                if (c.getInt("port", 0) == port && (hip.equals(ip) || (loop && ip.equals(authHost)))) {
                                    found = saved;
                                    break;
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // Aurora found: versions must match, else we don't enter — Aurora знайдено: версії мають збігатись, інакше не заходимо
            if (found != null) {
                int theirs = remoteProtocol(found);
                if (theirs >= 0 && theirs != PROTOCOL) {
                    protoMismatch(theirs);
                    Core.app.post(() -> done.get(true)); // handled: neither Aurora nor a normal game — оброблено: ні Aurora, ні звичайна гра
                    return;
                }
            }

            String base = found;
            Core.app.post(() -> {
                if (base != null) {
                    Core.settings.put("aurora-authority", base);
                    authorityOk = true;
                    ui.loadfrag.hide();
                    // Guest on someone's planet: separate temporary slot so planet A never mixes with player B's own Aurora
                    // — Гість на чужій планеті: окремий тимчасовий слот, щоб планета А не змішалась з власною Aurora гравця Б
                    wipeGuestData();
                    ui.menufrag.switchCampaign(slotGuest);
                    guestSession = true;
                    ui.planet.show();
                }
                done.get(base != null);
            });
        });
    }

    // ---------- Hooks — Хуки ----------

    public static void init() {
        if (inited) return;
        inited = true;

        // One timer for the whole game — Один таймер на всю гру
        Timer.schedule(() -> {
            if (sessionActive()) {
                syncUnlocks();
                refreshItems();
                refreshMap();
            }
        }, 5f, 5f);
        Runtime.getRuntime().addShutdownHook(new Thread(AuroraNet::shutdownWork, "aurora-shutdown"));
        Events.on(DisposeEvent.class, e -> shutdownWork());

        // Upload the sector on important events — Заливати сектор при важливих подіях
        Events.on(WaveEvent.class, e -> pushNow(false, "wave"));
        Events.on(SectorCaptureEvent.class, e -> pushNow(true, "capture"));
        Events.on(SectorLaunchEvent.class, e -> pushNow(true, "launch"));
        Events.on(LaunchItemEvent.class, e -> pushNow(false, "launch items"));
        Events.on(ResearchEvent.class, e -> pushNow(false, "research"));

        // Minimized: on phones wait for the upload (≤2.5 s, Android gives ~5 s), on PC in background
        // — Згортання: на телефоні чекаємо заливання (≤2.5 с, Android дає ~5 с), на ПК — фоном
        Core.app.addListener(new ApplicationListener() {
            @Override
            public void pause() {
                if (mobile) pushBlocking(2500);
                else pushNow(true, "minimize");
            }
        });

        // Our research -> Authority (not what just came from it, not the normal campaign)
        // — Наші дослідження -> Authority (не те, що саме з нього прийшло, і не звичайна кампанія)
        Events.on(UnlockEvent.class, e -> {
            if (applyingRemote || !sessionActive()) return;
            String n = e.content.name, by = myName();
            mainExecutor.submit(() -> {
                try {
                    pushUnlock(n, by);
                } catch (Exception ex) {
                    Log.err("unlock push failed", ex);
                }
            });
        });

        // The sector we claimed has loaded: open our server — Сектор, який ми заклеймили, завантажився: відкриваємо свій сервер
        Events.on(WorldLoadEvent.class, e -> {
            if (!wantHost) return;
            wantHost = false;
            try {
                net.host(myPort());
                player.admin = true;
                Events.fire(new HostEvent());
                startBeat();
                Sector hs = current;
                if (hs != null) mainExecutor.submit(() -> {
                    try {
                        post(sectorPath(hs, "heartbeat") + asMe());
                    } catch (Exception ex) {
                        Log.err("first heartbeat failed", ex);
                    }
                });
                hostLive = true;
                Time.runTask(60f, () -> {
                    if (hosting && current == hs) syncStock(hs);
                });
                Time.runTask(120f, () -> selfCheck("старт", false));
            } catch (Exception ex) {
                ui.showException("@server.error", ex);
            }
        });

        Events.on(StateChangeEvent.class, e -> {
            if (e.to == State.paused) pushNow(false, "pause");

            // Host went to the menu some other way (not leave()): release the sector — Хост потрапив у меню іншим шляхом (не leave()): звільняємо сектор
            if (hostLive && e.to == State.menu) {
                Core.app.post(() -> {
                    if (!hostLive || !state.isMenu()) return;
                    hostLive = false;
                    Sector s = current;
                    hosting = false;
                    stopBeat();
                    current = null;
                    if (s != null) mainExecutor.submit(() -> release(s, "menu"));
                });
            }
        });

        // Client: lost connection or never connected (3 s) -> back to the planet — Клієнт: зв'язок втрачено або так і не підключились (3 с) -> на карту
        Events.run(Trigger.update, () -> {
            if (!asClient) return;
            if (net.client() && state.isGame()) connectedOnce = true;
            boolean lost = connectedOnce && !net.client();
            boolean failed = !connectedOnce && Time.timeSinceMillis(enterTime) > 3000 && !net.client();
            if (lost || failed) {
                endClient();
                returnFromSector();
            }
        });

        startWatch();
    }
}
