package mindustry;

import arc.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.core.GameState.*;
import mindustry.game.EventType.*;
import mindustry.type.*;
import mindustry.gen.*;
import arc.func.*;
import arc.struct.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static mindustry.Vars.*;

/**
 * Authority Client: sector claim, heartbeat, sector data upload/download.
 */
public class AuroraNet {
    /**
     * Версія протоколу Aurora (запити й відповіді між грою та Authority).
     * ЗБІЛЬШУЙ на 1 щоразу, коли міняєш формат запитів/відповідей або додаєш обов'язкові ендпоінти.
     * Гра й Authority з різною версією не розмовляють - гравці бачать "оновіть білд".
     */
    public static final int PROTOCOL = 2;

    static long lastProtoMsg;

    /** Показати (не частіше разу на 30 с), що версії не збігаються. */
    static void protoMismatch(int theirs) {
        Core.app.post(() -> {
            if (Time.timeSinceMillis(lastProtoMsg) < 30000) return;
            lastProtoMsg = Time.millis();
            ui.loadfrag.hide();
            ui.showErrorMessage("Aurora: різні версії гри.\nУ тебе протокол " + PROTOCOL + ", у хоста планети " +
                (theirs <= 0 ? "старіший (без перевірки версії)" : String.valueOf(theirs)) + ".\nОновіть AURORA до однакового білда.");
        });
    }

    /** -1 = Authority недоступний, 0 = старий Authority без /version, інакше його версія протоколу. */
    static int remoteProtocol(String base) {
        try {
            Res r = request(base, "GET", "/version", null, null, 2000);
            if (r.code == 404) return 0;
            if (r.code != 200) return -1;
            return new JsonReader().parse(new String(r.body, StandardCharsets.UTF_8)).getInt("proto", 0);
        } catch (Throwable e) {
            return -1;
        }
    }
    /**
     * Адреса Authority: на своїй планеті - свій (127.0.0.1), у гостя - збережена адреса хоста планети.
     * Свій Authority більше не перезаписує "aurora-authority", тож адреса друга не губиться.
     */
    static String authority() {
        if (planetHost()) return "http://127.0.0.1:" + AuroraAuthority.port();
        return Core.settings.getString("aurora-authority", "http://localhost:8443");
    }

    /** Ми - хост планети: свій Authority запущений і ми на своїй планеті (не гість у друга). */
    public static boolean planetHost() {
        return localAuthority && campaignSlot == slotAurora && AuroraAuthority.port() > 0;
    }

    public static String myIp() {
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

    public static int myPort() {
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
    static volatile boolean shutdownDone;
    static int beatFails;
    static boolean forcePush;
    static mindustry.ui.dialogs.AuroraPickerDialog picker;

    public static class Claim {
        public boolean host;
        /** сектор тимчасово закритий (хост зник, йде перевірка / оновлення) */
        public boolean wait;
        public String state;
        public int retry;
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


    /** Рядок для JSON з екрануванням лапок, \\ і керівних символів. */
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
        if (res.code == 426) {
            int theirs = 0;
            try {
                theirs = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8)).getInt("proto", 0);
            } catch (Throwable ignored) {
            }
            protoMismatch(theirs);
        }
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
        String body = "{\"ip\":" + js(myIp()) + ",\"port\":" + myPort() + ",\"player\":" + js(me()) + "}";
        Res res = request("POST", "/sector/" + sid(s) + "/claim", "application/json", body.getBytes(StandardCharsets.UTF_8));
        if (res.code != 200) throw new RuntimeException("claim -> " + res.code);
        JsonValue v = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8));
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
        return auroraSlot() && (asClient || Time.timeSinceMillis(clientEnd) < 5000);
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

                if (c.wait) {
                    //хост сектора зник: чекаємо перевірку (до 30 с) і оновлення (15 с), потім пробуємо знову
                    Core.app.post(() -> waitForSector(sector, c));
                    return;
                }

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

    static boolean waitCancelled;

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
            syncStock(s);
            boolean up = ++beats % 18 == 0 || forcePush;
            forcePush = false;
            if (beats % 3 == 0) selfCheck("beat", true);
            byte[][] pack = up ? snapshot(s) : null;
            if (pack != null) lastPush = Time.millis();
            String stk = stockJson();
            boolean reclaim = beatFails > 0;
            mainExecutor.submit(() -> {
                boolean ok = false;
                try {
                    if (reclaim) {
                        Claim c = claim(s);
                        if (c.wait) throw new RuntimeException("сектор тимчасово закритий, повторимо");
                        if (!c.host) {
                            //поки ми були без зв'язку, сектор забрав інший гравець: його світ головний, наш - ні
                            Core.app.post(() -> lostSector(s));
                            return;
                        }
                    }
                    Res hb = request("POST", "/sector/" + sid(s) + "/heartbeat?player=" + q(me()), "text/plain", new byte[0]);
                    //404: Authority нас забув (перезапуск) - наступний beat перезаклеймить сектор
                    if (hb.code == 404) throw new RuntimeException("heartbeat: більше не хост, перезаклеймимо");
                    if (pack != null) push(s, pack);
                    putStock(s, stk);
                    ok = true;
                } catch (Exception e) {
                    Log.err("beat failed", e);
                }
                boolean good = ok;
                Core.app.post(() -> onBeat(good));
            });
        }), 10f, 10f);
    }

    /**
     * Нас витіснили з хостингу сектора (інший гравець став хостом, поки ми були без зв'язку з Authority).
     * Щоб не було двох копій одного сектора: закриваємо свій сервер (наші клієнти повернуться на карту),
     * нічого не заливаємо в Authority і підключаємось до нового хоста як клієнт.
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

    // ---------- Збереження сектора при важливих подіях ----------

    static long lastPush;
    static final long PUSH_COOLDOWN = 20_000L;

    /**
     * Знімок сектора й заливання в Authority просто зараз (не чекаючи 3-хвилинного beat).
     * force - ігнорувати паузу між заливаннями (захоплення, запуск).
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
                    forcePush = true; //наступний beat спробує ще раз
                }
            });
        });
    }

    /**
     * Гру згортають (Android може її вбити): знімок і заливання, чекаємо до timeoutMs.
     * Викликається з pause() на головному потоці.
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
    /** Хост сектора, в якому ми були клієнтом, закрив сервер. Викликається з NetClient.kick. */
    /** Виклик із switchCampaign: при переході у звичайну кампанію гасимо стан гостя Aurora. */
    /**
     * Стирає все, що лишилось від попередньої гостьової сесії: сейви секторів (saves-guest/) і ключі "cg-"
     * (дослідження, info секторів, лічильники). Справжні дані планети живуть у її Authority.
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

    public static void onCampaignSlot(int slot) {
        if (slot != slotNormal) return;
        guestSession = false;
        watchFails = 0;
        remoteBases = new ObjectSet<>();
        liveSectors = new ObjectSet<>();
        remoteState = new ObjectIntMap<>();
    }

    public static void hostLeft() {
        if (!asClient) return;
        asClient = false;
        connectedOnce = false;
        current = null;
        clientEnd = Time.millis();
        ui.showInfoToast("[accent]Aurora:[] хост вийшов із сектора", 4f);
        returnFromSector();
    }

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
        String stk = stockJson();
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

    // ---------- Карта: захоплені сектори інших гравців ----------

    /** Колір союзників (чужі бази / живі хости) на картах. */
    public static final arc.graphics.Color allyColor = arc.graphics.Color.valueOf("5aa7ff");

    static volatile ObjectSet<String> remoteBases = new ObjectSet<>(), liveSectors = new ObjectSet<>();
    /** стан секторів з Authority: 1 = ядро, 2 = хвилі, 4 = атака */
    static volatile ObjectIntMap<String> remoteState = new ObjectIntMap<>();

    public static boolean remoteWaves(Sector s) {
        return s != null && sessionActive() && (remoteState.get(sid(s), 0) & 2) != 0;
    }

    public static boolean remoteAttack(Sector s) {
        return s != null && sessionActive() && (remoteState.get(sid(s), 0) & 4) != 0;
    }

    /**
     * Позначки живих секторів на картах (і фон меню, і карта кампанії). Звичайна рамка бази малюється до цього.
     * - хост на вже захопленій базі: рамка стає синьою;
     * - хтось захоплює сектор (хвилі ще йдуть / бази ще нема): блимає синім, помаранчева рамка лишається;
     * - хтось на ворожій базі (атака): блимає червоним.
     */
    public static void drawLiveSector(mindustry.graphics.g3d.PlanetRenderer planets, Sector sec, boolean base, boolean waves, boolean attack, float alpha) {
        if (!liveSector(sec)) return;
        float pulse = arc.math.Mathf.absin(Time.time, 7f, 1f);
        arc.graphics.Color c = arc.util.Tmp.c1;
        if (attack || (!base && sec.hasEnemyBase())) {
            arc.graphics.Color red = mindustry.game.Team.crux.color;
            planets.fill(sec, c.set(red).a(alpha * (0.15f + 0.35f * pulse)), -0.001f);
            planets.drawBorders(sec, red, alpha * (0.5f + 0.5f * pulse));
        } else if (!base || waves) {
            planets.fill(sec, c.set(allyColor).a(alpha * (0.15f + 0.35f * pulse)), -0.001f);
        } else {
            planets.drawSelection(sec, c.set(allyColor).a(alpha), 0.026f, -0.001f);
        }
    }

    static boolean sessionActive() {
        return auroraSlot() && (localAuthority || guestSession || hosting || asClient);
    }

    /** Сектор захоплений кимось у спільній кампанії (за даними Authority). */
    public static boolean remoteBase(Sector s) {
        return s != null && sessionActive() && remoteBases.contains(sid(s));
    }

    /** У секторі зараз є живий хост. */
    public static boolean liveSector(Sector s) {
        return s != null && sessionActive() && liveSectors.contains(sid(s));
    }

    public static void refreshMap() {
        if (!sessionActive()) return;
        mainExecutor.submit(() -> {
            try {
                ObjectSet<String> bases = new ObjectSet<>(), live = new ObjectSet<>();
                ObjectIntMap<String> st = new ObjectIntMap<>();
                Res cap = request("GET", "/sectors/state", null, null);
                if (cap.code == 200) {
                    for (JsonValue c = new JsonReader().parse(new String(cap.body, StandardCharsets.UTF_8)).child; c != null; c = c.next) {
                        int bits = c.asInt();
                        st.put(c.name, bits);
                        if ((bits & 1) != 0) bases.add(c.name);
                    }
                }
                Res lv = request("GET", "/sectors", null, null);
                if (lv.code == 200) {
                    for (JsonValue c = new JsonReader().parse(new String(lv.body, StandardCharsets.UTF_8)).child; c != null; c = c.next) live.add(c.name);
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

    // ---------- Tech-tree ----------

    static boolean applyingRemote;

    static String myName() {
        return player == null ? "" : Strings.stripColors(player.name);
    }

    /**
     * Застосовує дослідження інших гравців як справжні (з подією UnlockEvent):
     * - з'являються в меню будівництва і дереві досліджень одразу;
     * - якщо ми хост сектора, гра сама шле Call.researched своїм клієнтам;
     * - показується сповіщення, хто що дослідив.
     */
    static void applyRemoteUnlocks(Seq<mindustry.ctype.UnlockableContent> fresh, ObjectMap<String, String> by) {
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
                //масова синхронізація (перший вхід): без лавини сповіщень
                for (var u : fresh) {
                    u.quietUnlock();
                    if (net.server()) Call.researched(u);
                }
                //одна подія, щоб перебудувались меню будівництва і дерево досліджень
                Events.fire(new UnlockEvent(fresh.peek()));
                if (state.isGame()) ui.hudfrag.showToast(Icon.book, "[accent]Aurora:[] отримано досліджень: " + fresh.size);
            }
        } finally {
            applyingRemote = false;
        }
    }

    /**
     * Тягне спільні розблокування з Authority, а свої, яких там ще нема, заливає. Лише мультикампанія.
     */
    public static void syncUnlocks() {
        mainExecutor.submit(() -> {
            try {
                Res res = request("GET", "/unlocks?full=1", null, null);
                if (res.code != 200) return;

                //назва -> хто дослідив
                ObjectMap<String, String> remote = new ObjectMap<>();
                JsonValue obj = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8));
                if (obj.isArray()) {
                    for (JsonValue c = obj.child; c != null; c = c.next) remote.put(c.asString(), "");
                } else {
                    for (JsonValue c = obj.child; c != null; c = c.next) remote.put(c.name, c.asString());
                }

                Core.app.post(() -> {
                    if (!auroraSlot()) return;
                    Seq<String> missing = new Seq<>();
                    Seq<mindustry.ctype.UnlockableContent> fresh = new Seq<>();
                    content.each(c -> {
                        if (!(c instanceof mindustry.ctype.UnlockableContent u)) return;
                        if (remote.containsKey(u.name)) {
                            if (!u.unlockedLocal()) fresh.add(u);
                        } else if (u.unlocked() && !u.alwaysUnlocked) {
                            missing.add(u.name);
                        }
                    });
                    applyRemoteUnlocks(fresh, remote);
                    if (missing.size > 0) mainExecutor.submit(() -> {
                        for (String n : missing) {
                            try {
                                request("GET", "/unlock/" + q(n) + "?by=" + q(myName()), null, null);
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

// ---------- Спільні ресурси ----------

    static volatile ItemSeq shared = new ItemSeq();

    /** Чи вести ресурси через Authority (інакше ванільна логіка). */
    public static boolean itemsActive(){
        return auroraSlot() && (localAuthority || guestSession || hosting || asClient);
    }

    public static ItemSeq globalItems(){
        return shared.copy();
    }

    public static void refreshItems(){
        if(!itemsActive()) return;
        mainExecutor.submit(() -> {
            try{
                Res res = request("GET", "/items", null, null);
                if(res.code != 200) return;
                ItemSeq seq = new ItemSeq();
                JsonValue obj = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8));
                for(JsonValue c = obj.child; c != null; c = c.next){
                    Item it = content.item(c.name);
                    if(it != null) seq.set(it, c.asInt());
                }
                Core.app.post(() -> {
                    shared = seq;
                    if(ui.research.isShown()) ui.research.rebuildItems();
                });
            }catch(Throwable t){
                Log.err("refreshItems failed", t);
            }
        });
    }

    /**
     * Списання з підтвердженням: done(true) лише якщо Authority прийняв витрату (200).
     * 409 = не вистачає, будь-яка помилка = нема зв'язку. done викликається на головному потоці.
     */
    public static void spendConfirmed(ObjectIntMap<Item> need, Boolc done) {
        StringBuilder items = new StringBuilder("{");
        for (var e : need) {
            if (items.length() > 1) items.append(',');
            items.append('"').append(e.key.name).append("\":").append(e.value);
        }
        String body = "{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"items\":" + items.append('}') + "}";
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

    /** Викликається з ResearchDialog при витраті. */
    public static void spend(Item item, int amount){
        if(amount <= 0) return;
        String body = "{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"items\":{\"" + item.name + "\":" + amount + "}}";
        mainExecutor.submit(() -> {
            try{
                Res res = request("POST", "/spend", "application/json", body.getBytes(StandardCharsets.UTF_8));
                if(res.code != 200) Log.warn("spend rejected: @", res.code);
            }catch(Exception e){
                Log.err("spend failed", e);
            }
            refreshItems();
        });
    }

    static String stockJson(){
        StringBuilder sb = new StringBuilder("{");
        var core = state.rules.defaultTeam.core();
        if(core != null){
            for(Item it : content.items()){
                int a = core.items.get(it);
                if(a <= 0) continue;
                if(sb.length() > 1) sb.append(',');
                sb.append('"').append(it.name).append("\":").append(a);
            }
        }
        return sb.append('}').toString();
    }

    static void putStock(Sector s, String json) throws Exception{
        putBytes("/sector/" + sid(s) + "/items?player=" + q(me()), json.getBytes(StandardCharsets.UTF_8));
    }

    /** Хост: забрати чергу списань, застосувати до ядра, підтвердити, оновити запас. */
    static void syncStock(Sector s){
        mainExecutor.submit(() -> {
            try{
                Res r = request("GET", "/sector/" + sid(s) + "/pending?player=" + q(me()), null, null);
                JsonValue arr = r.code == 200 ? new JsonReader().parse(new String(r.body, StandardCharsets.UTF_8)) : null;
                Core.app.post(() -> {
                    if(!hosting || current != s || !state.isGame() || state.rules.sector != s) return;
                    var core = state.rules.defaultTeam.core();
                    StringBuilder acked = new StringBuilder();
                    if(arr != null){
                        for(JsonValue c = arr.child; c != null; c = c.next){
                            Item it = content.item(c.getString("item"));
                            if(it != null && core != null) core.items.remove(it, Math.min(c.getInt("amount"), core.items.get(it)));
                            if(acked.length() > 0) acked.append(',');
                            acked.append(c.getString("id"));
                        }
                    }
                    String body = stockJson();
                    mainExecutor.submit(() -> {
                        try{
                            putStock(s, body);
                            if(acked.length() > 0) post("/sector/" + sid(s) + "/ack?player=" + q(me()) + "&ids=" + q(acked.toString()));
                        }catch(Exception e){
                            Log.err("syncStock push failed", e);
                        }
                    });
                });
            }catch(Throwable t){
                Log.err("syncStock failed", t);
            }
        });
    }
    // ---------- Сторож Authority ----------


    /**
     * Запускається ОДИН раз з init(). Поки ми в Aurora як гість або хост сектора, перевіряє, чи живий господар планети.
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


            //Authority за цією адресою живе, поки гра запущена, навіть коли хост грає звичайну кампанію.
            //Тому Aurora лише якщо: хост зараз у режимі Aurora, або цей ip:port - живий Aurora-сектор.
            try {
                Res mode = request(direct, "GET", "/mode", null, null, 2000);
                if (mode.code == 200 && new JsonReader().parse(new String(mode.body, StandardCharsets.UTF_8)).getBoolean("active", false)) {
                    found = direct;
                } else if (mode.code == 200 || mode.code == 404) {
                    Res res = request(direct, "GET", "/sectors", null, null, 2000);
                    if (res.code == 200) {
                        for (JsonValue c = new JsonReader().parse(new String(res.body, StandardCharsets.UTF_8)).child; c != null; c = c.next) {
                            if (c.getInt("port", 0) == port) {
                                found = direct;
                                break;
                            }
                        }
                    }
                }
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

            //знайшли Aurora - перевіряємо, що версії збігаються, інакше не заходимо
            if (found != null) {
                int theirs = remoteProtocol(found);
                if (theirs >= 0 && theirs != PROTOCOL) {
                    protoMismatch(theirs);
                    Core.app.post(() -> done.get(true)); //оброблено: в Aurora не заходимо, у звичайну гру теж
                    return;
                }
            }

            String base = found;
            Core.app.post(() -> {
                if (base != null) {
                    Core.settings.put("aurora-authority", base);
                    authorityOk = true;
                    ui.loadfrag.hide();
                    //гість на чужій планеті: окремий тимчасовий слот, щоб планета А не змішалась з власною Aurora гравця Б
                    wipeGuestData();
                    ui.menufrag.switchCampaign(slotGuest);
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
        //один таймер на всю гру (раніше створювався при кожному виклику init)
        Timer.schedule(() -> {
            if(sessionActive()){
                syncUnlocks();
                refreshItems();
                refreshMap();
            }
        }, 5f, 5f);
        Runtime.getRuntime().addShutdownHook(new Thread(AuroraNet::shutdownWork, "aurora-shutdown"));
        Events.on(DisposeEvent.class, e -> shutdownWork());

        //заливати сектор при важливих подіях
        Events.on(WaveEvent.class, e -> pushNow(false, "wave"));
        Events.on(SectorCaptureEvent.class, e -> pushNow(true, "capture"));
        Events.on(SectorLaunchEvent.class, e -> pushNow(true, "launch"));
        Events.on(LaunchItemEvent.class, e -> pushNow(false, "launch items"));
        Events.on(ResearchEvent.class, e -> pushNow(false, "research"));
        Events.on(StateChangeEvent.class, e -> {
            if (e.to == State.paused) pushNow(false, "pause");
        });

        //гру згортають: на телефоні чекаємо заливання (до 2.5 с, Android дає ~5 с), на ПК - фоном
        Core.app.addListener(new ApplicationListener() {
            @Override
            public void pause() {
                if (mobile) pushBlocking(2500);
                else pushNow(true, "minimize");
            }
        });

        Events.on(UnlockEvent.class, e -> {
            //не пушимо назад те, що саме прийшло з Authority, і не змішуємо зі звичайною кампанією
            if(applyingRemote || !sessionActive()) return;
            String n = e.content.name;
            String by = myName();
            mainExecutor.submit(() -> {
                try {
                    request("GET", "/unlock/" + q(n) + "?by=" + q(by), null, null);
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
                Time.runTask(60f, () -> { if(hosting && current == hs) syncStock(hs); });
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