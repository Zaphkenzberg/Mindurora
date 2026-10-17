package mindustry;

import arc.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.core.GameState.*;
import mindustry.game.EventType.*;
import mindustry.type.*;
import arc.func.*;
import arc.struct.*;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import static mindustry.Vars.*;

import static mindustry.Vars.*;

/**
 * Authority Client: sector claim, heartbeat, sector data upload/download.
 */
public class AuroraNet {
    static String authority() {
        return Core.settings.getString("aurora-authority", "http://localhost:8443");
    }

    static String myIp() {
        String s = Core.settings.getString("aurora-ip", "");
        if (!s.isEmpty() && !s.equals("127.0.0.1")) return s;
        String fallback = null;
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("100.")) return ip;                       // Tailscale
                    if (fallback == null && !ip.startsWith("192.168.56.")) fallback = ip;  // не VirtualBox
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "127.0.0.1";
    }

    static int myPort() {
        return Core.settings.getInt("port", port);
    }


    static String me() {
        return platform.getUUID();
    }

    public static volatile boolean localAuthority;
    public static volatile boolean authorityOk;
    public static boolean hosting, asClient;
    static boolean wantHost, connectedOnce, inited, hostLive;
    static Sector current;
    static long enterTime;
    static Timer.Task beat;
    static int beats;
    static int dbgSec = -1;
    static boolean guestSession;
    static int watchFails;
    static long lastGoneMsg;
    static long clientEnd;
    static SSLSocketFactory sslFactory;
    static volatile boolean shutdownDone;
    static int beatFails;
    static boolean forcePush;
    static mindustry.ui.dialogs.AuroraPickerDialog picker;

    public static class Claim {
        public boolean host;
        public String ip;
        public int port;
    }

    /**
     * HTTP
     */

    static class Res {
        int code;
        byte[] body;
    }


    static SSLSocketFactory ssl() throws Exception {
        if (sslFactory == null) {
            // DEV: довіряємо самопідписаному сертифікату (потрібно лише якщо Authority на https)
            TrustManager[] trustAll = {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            sslFactory = ctx.getSocketFactory();
        }
        return sslFactory;
    }

    static String q(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String sid(Sector s) {
        return s.planet.name + "-" + s.id;
    }

    static Res request(String method, String path, String contentType, byte[] data) throws Exception {
        return request(authority(), method, path, contentType, data, 5000);
    }

    /**
     * Windows - "Address already in use"
     */
    static Res request(String base, String method, String path, String contentType, byte[] data, int connectTimeout) throws Exception {
        try {
            return request0(base, method, path, contentType, data, connectTimeout);
        } catch (java.net.SocketException e) {
            Thread.sleep(300);
            return request0(base, method, path, contentType, data, connectTimeout);
        }
    }

    static Res request0(String base, String method, String path, String contentType, byte[] data, int connectTimeout) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(base + path).openConnection();
        if (con instanceof HttpsURLConnection) {
            ((HttpsURLConnection) con).setSSLSocketFactory(ssl());
            ((HttpsURLConnection) con).setHostnameVerifier((host, session) -> true);
        }
        con.setConnectTimeout(connectTimeout);
        con.setReadTimeout(15000);
        con.setRequestMethod(method);
        con.setRequestProperty("Connection", "close");
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
        return res;
    }

    static void post(String path) throws Exception {
        request("POST", path, "text/plain", new byte[0]);
    }

    static void putBytes(String path, byte[] data) throws Exception {
        Res res = request("PUT", path, "application/octet-stream", data);
        if (res.code != 200) throw new RuntimeException("PUT " + path + " -> " + res.code);
    }

    static byte[] getBytes(String path) throws Exception {
        Res res = request("GET", path, null, null);
        return res.code == 200 ? res.body : null;
    }

    static Claim claim(Sector s) throws Exception {
        String body = "{\"ip\":\"" + myIp() + "\",\"port\":" + myPort() + ",\"player\":\"" + me() + "\"}";
        Res res = request("POST", "/sector/" + sid(s) + "/claim", "application/json", body.getBytes(StandardCharsets.UTF_8));
        if (res.code != 200) throw new RuntimeException("claim -> " + res.code);
        JsonValue v = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8));
        Claim c = new Claim();
        c.host = v.getString("role").equals("host");
        c.ip = v.getString("ip", null);
        c.port = v.getInt("port", 0);
        return c;
    }

    /**
     Data Sector
     */


    /**
     * save + info
     */
    static byte[][] snapshot(Sector s) {
        try {
            if (!(state.isGame() && state.rules.sector == s)) return null;
            s.info.prepare(s);
            control.saves.saveSector(s);
            return new byte[][]{
                    s.save.file.readBytes(),
                    s.exportInfo().getBytes(StandardCharsets.UTF_8)
            };
        } catch (Exception e) {
            Log.err("snapshot failed", e);
            return null;
        }
    }

    static void push(Sector s, byte[][] pack) throws Exception {
        String p = "?player=" + q(me());
        putBytes("/sector/" + sid(s) + "/save" + p, pack[0]);
        putBytes("/sector/" + sid(s) + "/info" + p, pack[1]);
    }

    /**
     * Вхід в сектор
     */
    public static boolean auroraClient() {
        return campaignSlot == 1 && (asClient || Time.timeSinceMillis(clientEnd) < 5000);
    }

    /**
     * Вибір іншого сектора на карті: хост спершу чисто виходить, гість тихо відключається, потім обидва заходять у новий.
     */
    public static void switchTo(Sector sector) {
        if (sector == current && (hosting || asClient)) return;
        if (hosting) {
            leave(() -> enter(sector));
            return;
        }
        if (asClient) {
            asClient = false;
            connectedOnce = false;
            current = null;
            clientEnd = Time.millis();
            netClient.disconnectQuietly();
            logic.reset();
        }
        enter(sector);
    }

    /**
     * Діагностика: чи живий Arc-сервер у цій копії (UDP-пінг самого себе).
     */
    static void selfCheck(String tag, boolean quietOk) {
        net.pingHost("127.0.0.1", myPort(),
                h -> {
                    if (!quietOk) ui.showInfoToast("[accent]Aurora " + tag + ": сервер живий", 3f);
                },
                e -> ui.showInfoToast("[scarlet]Aurora " + tag + ": сервер НЕ відповідає", 8f));
    }

    /**
     * Чекає до secs секунд, поки хост почне слухати порт.
     */
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


    /**
     * Один раз: знімок -> заливка -> release -> зупинка Authority.
     */
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
            try {
                post("/sector/" + sid(s) + "/release?player=" + q(me()));
            } catch (Throwable t) {
                Log.err("shutdown: release failed", t);
            }
        }
        if (localAuthority) AuroraAuthority.stop();   // спершу знімок/release, потім stop
    }

    /**
     * Гравець вибрав сектор: стаємо хостом або підключаємось до існуючого.
     */
    public static void enter(Sector sector) {
        enter(sector, false);
    }

    static void enter(Sector sector, boolean retried) {
        syncUnlocks();
        ui.loadfrag.show("Aurora...");
        mainExecutor.submit(() -> {
            try {
                Claim c = claim(sector);

                if (c.host) {
                    byte[] save = getBytes("/sector/" + sid(sector) + "/save");
                    byte[] info = getBytes("/sector/" + sid(sector) + "/info");
                    Core.app.post(() -> startHosting(sector, save, info));
                } else {
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

    // ---------- Heartbeat і вихід ----------


    static void startBeat() {
        stopBeat();
        beats = 0;
        beatFails = 0;
        forcePush = false;
        beat = Timer.schedule(() -> Core.app.post(() -> {
            Sector s = current;
            if (!hosting || s == null) return;
            boolean up = ++beats % 18 == 0 || forcePush;
            forcePush = false;
            if (beats % 3 == 0) selfCheck("beat", true);
            byte[][] pack = up ? snapshot(s) : null;
            boolean reclaim = beatFails > 0;
            mainExecutor.submit(() -> {
                boolean ok = false;
                try {
                    if (reclaim) {
                        Claim c = claim(s);
                        if (!c.host) throw new RuntimeException("сектор уже зайнятий іншим гравцем");
                    }
                    post("/sector/" + sid(s) + "/heartbeat?player=" + q(me()));
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
     * Вийшли з сектора (хост зник, ми вийшли самі, або це був наш Quit): планета, якщо Authority живий, інакше головне меню.
     */
    static void returnFromSector() {
        checkAuthority(() -> {
            if (authorityOk) {
                guestSession = !localAuthority;
                ui.planet.show();
            } else {
                guestSession = false;
                if (net.client()) netClient.disconnectQuietly();
                ui.planet.hide();
                showGone("Aurora: Authority недоступний.\nПовертаємось у головне меню.");
            }
        });
    }

    static void stopBeat() {
        if (beat != null) {
            beat.cancel();
            beat = null;
        }
    }

    /**
     * Показує повідомлення про зникнення лише раз на 10 с, щоб діалоги не накопичувались.
     */
    static void showGone(String msg) {
        if (Time.timeSinceMillis(lastGoneMsg) < 10000) return;
        lastGoneMsg = Time.millis();
        ui.showErrorMessage(msg);
    }

    public static void leave() {
        leave(null);
    }

    /**
     * Чистий вихід хоста: зберегти -> залити -> звільнити сектор -> закрити сервер. then (якщо є) викликається замість повернення на планету.
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
        byte[][] pack = snapshot(s);   // світ ще живий, робимо знімок
        net.closeServer();             // порт звільняємо ДО release
        ui.loadfrag.show("Aurora: зберігаю...");
        mainExecutor.submit(() -> {
            try {
                if (pack != null) push(s, pack);
            } catch (Exception e) {
                Log.err("leave: push failed", e);
            }
            try {
                post("/sector/" + sid(s) + "/release?player=" + q(me()));
            } catch (Exception e) {
                Log.err("leave: release failed", e);
            }
            Core.app.post(() -> {
                ui.loadfrag.hide();
                logic.reset();
                current = null;
                if (then != null) then.run();
                else returnFromSector();
            });
        });
    }


    public static void openPicker() {
        syncUnlocks();
        if (picker == null) picker = new mindustry.ui.dialogs.AuroraPickerDialog();
        picker.show();
    }

    public static class Entry {
        public String id, ip;
        public int port;
        public boolean hosted;
    }

    static Entry entry(Seq<Entry> list, String id) {
        for (Entry e : list) if (e.id.equals(id)) return e;
        Entry e = new Entry();
        e.id = id;
        list.add(e);
        return e;
    }

    public static Sector sectorById(String id) {
        int i = id.lastIndexOf('-');
        if (i < 0) return null;
        Planet p = content.getByName(mindustry.ctype.ContentType.planet, id.substring(0, i));
        if (p == null) return null;
        int n = Strings.parseInt(id.substring(i + 1), -1);
        return n >= 0 && n < p.sectors.size ? p.sectors.get(n) : null;
    }

    /**
     * Список секторів для UI: стартовий + з даними + зайняті.
     */
    public static void fetchSectors(Cons<Seq<Entry>> done) {
        mainExecutor.submit(() -> {
            Seq<Entry> out = new Seq<>();
            try {
                entry(out, sid(mindustry.content.SectorPresets.groundZero.sector));
                for (SectorPreset p : content.sectors()) {
                    if (p.sector != null) entry(out, p.planet.name + "-" + p.sector.id);
                }
                Res known = request("GET", "/sectors/known", null, null);
                if (known.code == 200) {
                    JsonValue arr = new JsonReader().parse(new String(known.body, StandardCharsets.UTF_8));
                    for (JsonValue c = arr.child; c != null; c = c.next) entry(out, c.asString());
                }

                Res live = request("GET", "/sectors", null, null);
                if (live.code == 200) {
                    JsonValue obj = new JsonReader().parse(new String(live.body, StandardCharsets.UTF_8));
                    for (JsonValue c = obj.child; c != null; c = c.next) {
                        Entry e = entry(out, c.name);
                        e.hosted = true;
                        e.ip = c.getString("ip", "?");
                        e.port = c.getInt("port", 0);
                    }
                }
            } catch (Throwable t) {
                Log.err("fetchSectors failed", t);
                out.clear();
            }
            Core.app.post(() -> done.get(out));
        });
    }

    // ---------- Tech-tree ----------

    /**
     * Тягне спільні розблокування з Authority, а свої, яких там ще нема, заливає. Лише мультикампанія.
     */
    public static void syncUnlocks() {
        if (campaignSlot != 1) return;
        mainExecutor.submit(() -> {
            try {
                Res res = request("GET", "/unlocks", null, null);
                if (res.code != 200) return;

                ObjectSet<String> remote = new ObjectSet<>();
                JsonValue arr = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8));
                for (JsonValue c = arr.child; c != null; c = c.next) remote.add(c.asString());

                Core.app.post(() -> {
                    if (net.client()) return;
                    Seq<String> missing = new Seq<>();
                    content.each(c -> {
                        if (!(c instanceof mindustry.ctype.UnlockableContent u)) return;
                        if (remote.contains(u.name)) {
                            if (!u.unlocked()) u.quietUnlock();
                        } else if (u.unlocked() && !u.alwaysUnlocked) {
                            missing.add(u.name);
                        }
                    });
                    if (missing.size > 0) mainExecutor.submit(() -> {
                        for (String n : missing) {
                            try {
                                request("GET", "/unlock/" + q(n), null, null);
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

    // ---------- Сторож Authority ----------

    /**
     * Запускається ОДИН раз з init(). Поки ми в Aurora як гість або хост сектора, перевіряє, чи живий господар планети.
     */
    static void startWatch() {
        Timer.schedule(() -> Core.app.post(() -> {
            if (localAuthority) {
                watchFails = 0;
                return;
            }   // ми самі господар планети
            if (campaignSlot != 1) return;
            boolean active = hosting || asClient || (guestSession && ui.planet.isShown());
            if (!active) {
                guestSession = false;
                watchFails = 0;
                return;
            }
            mainExecutor.submit(() -> {
                boolean ok = false;
                try {
                    ok = request(authority(), "GET", "/sectors", null, null, 3000).code == 200;
                } catch (Throwable e) {
                }
                boolean good = ok;
                Core.app.post(() -> {
                    authorityOk = good;
                    if (good) {
                        watchFails = 0;
                    } else if (++watchFails >= 3) {
                        authorityGone();
                    }
                });
            });
        }), 5f, 5f);
    }

    static void authorityGone() {
        if (localAuthority) return;
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
        asClient = false;
        connectedOnce = false;
        current = null;
        if (wasClient) netClient.disconnectQuietly();
        logic.reset();
        ui.planet.hide();
        showGone("Aurora: господар планети зник.\nПовертаємось у головне меню.");
    }

    /**
     * Тиха перевірка: onChange викликається на головному потоці, лише якщо стан змінився.
     */
    public static void watchAuthority(Runnable onChange) {
        mainExecutor.submit(() -> {
            boolean was = authorityOk, ok = false;
            try {
                ok = request("GET", "/sectors", null, null).code == 200;
            } catch (Throwable ignored) {
            }
            authorityOk = ok;
            if (ok != was) Core.app.post(onChange);
        });
    }

    /**
     * Перевіряє, чи відповідає Authority; після перевірки викликає done на головному потоці.
     */
    public static void checkAuthority(Runnable done) {
        mainExecutor.submit(() -> {
            boolean ok = false;
            try {
                ok = request("GET", "/sectors", null, null).code == 200;
            } catch (Throwable ignored) {
            }
            authorityOk = ok;
            Core.app.post(done);
        });
    }

    /**
     * Join Game: якщо за цією адресою (або через збережений Authority) це Aurora, входимо в режим Aurora.
     */
    public static void probeAndJoin(String ip, int port, Boolc done) {
        String host = ip.indexOf(':') >= 0 && !ip.startsWith("[") ? "[" + ip + "]" : ip;
        String direct = "http://" + host + ":8443";
        mainExecutor.submit(() -> {
            String found = null;


            try {
                if (request(direct, "GET", "/sectors", null, null, 2000).code == 200) found = direct;
            } catch (Throwable ignored) {
            }

            if (found == null) {
                String saved = authority();
                try {
                    if (!saved.equals(direct)) {
                        Res res = request(saved, "GET", "/sectors", null, null, 2000);
                        if (res.code == 200) {
                            JsonValue obj = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8));
                            String authHost = new URL(saved).getHost();
                            for (JsonValue c = obj.child; c != null; c = c.next) {
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

            String base = found;
            Core.app.post(() -> {
                if (base != null) {
                    Core.settings.put("aurora-authority", base);
                    authorityOk = true;
                    ui.loadfrag.hide();
                    ui.menufrag.switchCampaign(1);
                    guestSession = true;
                    ui.planet.show();
                }
                done.get(base != null);
            });
        });
    }

    static String authorityHost() {
        try {
            return new URL(authority()).getHost();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    // ---------- Хуки ----------

    public static void init() {

        if (inited) return;
        inited = true;
        Runtime.getRuntime().addShutdownHook(new Thread(AuroraNet::shutdownWork, "aurora-shutdown"));
        Events.on(DisposeEvent.class, e -> shutdownWork());

        Events.on(UnlockEvent.class, e -> {
            if (campaignSlot != 1 || net.client()) return;
            String n = e.content.name;
            mainExecutor.submit(() -> {
                try {
                    request("GET", "/unlock/" + q(n), null, null);
                } catch (Exception ex) {
                    Log.err("unlock push failed", ex);
                }
            });
        });

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
                        post("/sector/" + sid(hs) + "/heartbeat?player=" + q(me()));
                    } catch (Exception ex2) {
                        Log.err("first heartbeat failed", ex2);
                    }
                });
                hostLive = true;
                Time.runTask(120f, () -> selfCheck("старт", false));
            } catch (Exception ex) {
                ui.showException("@server.error", ex);
            }
        });

        Events.on(StateChangeEvent.class, e -> {
            if (hostLive && e.to == State.menu) {
                Core.app.post(() -> {
                    if (!hostLive || !state.isMenu()) return;
                    hostLive = false;
                    Sector s = current;
                    hosting = false;
                    stopBeat();
                    current = null;
                    if (s != null) mainExecutor.submit(() -> {
                        try {
                            post("/sector/" + sid(s) + "/release?player=" + q(me()));
                        } catch (Exception ex) {
                            Log.err("release failed", ex);
                        }
                    });
                });
            }
        });

        Events.run(Trigger.update, () -> {
            if (!asClient) return;
            long t = Time.timeSinceMillis(enterTime);
            if (t < 40000 && (int) (t / 1000) != dbgSec) {
                dbgSec = (int) (t / 1000);

            }
            if (net.client() && state.isGame()) connectedOnce = true;
            boolean lost = connectedOnce && !net.client();
            boolean failed = !connectedOnce && t > 3000 && !net.client();
            if (lost || failed) {
                asClient = false;
                clientEnd = Time.millis();
                connectedOnce = false;
                current = null;
                returnFromSector();
            }
        });

        startWatch();
    }
}