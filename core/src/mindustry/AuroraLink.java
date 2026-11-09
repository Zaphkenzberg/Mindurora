package mindustry;

import arc.*;
import arc.func.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.type.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

import static mindustry.AuroraUtil.*;
import static mindustry.Vars.*;
import static mindustry.AuroraJoin.PROBE_TIMEOUT;
import static mindustry.AuroraNet.PROTOCOL;
import static mindustry.AuroraNet.Res;
import static mindustry.AuroraNet.advertise;
import static mindustry.AuroraNet.asClient;
import static mindustry.AuroraNet.authority;
import static mindustry.AuroraNet.authorityOk;
import static mindustry.AuroraNet.current;
import static mindustry.AuroraNet.get;
import static mindustry.AuroraNet.guestSession;
import static mindustry.AuroraNet.hostLive;
import static mindustry.AuroraNet.hostPath;
import static mindustry.AuroraNet.hostToken;
import static mindustry.AuroraNet.hosting;
import static mindustry.AuroraNet.localAuthority;
import static mindustry.AuroraNet.parse;
import static mindustry.AuroraNet.planetHost;
import static mindustry.AuroraNet.post;
import static mindustry.AuroraNet.request;
import static mindustry.AuroraNet.sid;
import static mindustry.AuroraNet.snapshot;
import static mindustry.AuroraNet.watchFails;
import static mindustry.AuroraSectors.endClient;
import static mindustry.AuroraSectors.showGone;
import static mindustry.AuroraSectors.stopBeat;

/**
 * AuroraNet internals: talking to the Authority: protocol version, passwords, low-level requests, finding a planet from a sector, the Authority watch.
 * — Нутрощі AuroraNet: розмова з Authority: версія протоколу, паролі, запити низького рівня, пошук планети через сектор, сторож Authority.
 */
final class AuroraLink {

    private AuroraLink() {
    }

    // ---------- Protocol version — Версія протоколу ----------

    static long lastProtoMsg;

    /** Don't pop up the version dialog for this thread's requests (server list probe) — Не показувати вікно версії для запитів цього потоку (перевірка списку серверів). */
    static final ThreadLocal<Boolean> quietProto = ThreadLocal.withInitial(() -> false);

    /** Tell the player versions differ (at most once per 30 s) — Сказати гравцю, що версії різні (не частіше разу на 30 с). */
    static void protoMismatch(int theirs) {
        Core.app.post(() -> {
            if (Time.timeSinceMillis(lastProtoMsg) < 30000) return;
            lastProtoMsg = Time.millis();
            ui.loadfrag.hide();
            ui.showErrorMessage(tr("proto.mismatch", String.valueOf(PROTOCOL), theirs <= 0 ? tr("proto.old") : String.valueOf(theirs)));
        });
    }

    /** -1 unreachable, 0 old Authority without /version, else its protocol — -1 недоступний, 0 старий без /version, інакше його версія. */
    static int remoteProtocol(String base) {
        try {
            Res r = request(base, "GET", "/version", null, PROBE_TIMEOUT);
            if (r.code == 404) return 0;
            if (r.code != 200) return -1;
            return parse(r).getInt("proto", 0);
        } catch (Throwable e) {
            return -1;
        }
    }

    // ---------- Requests to Authority — Запити до Authority ----------

    /** Claim refused by the planet host (kick / ban), not a network error — Клейм відхилив власник планети (кік / бан), а не помилка мережі. */
    static class RemovedException extends RuntimeException {
        RemovedException(String msg) {
            super(msg);
        }
    }

    // ---------- Passwords — Паролі ----------

    /**
     * Random secret that only this PC knows; our own game sends it to our own Authority, which then treats the request as
     * the planet host's (a tunnel that makes strangers look like 127.0.0.1 can't fake it).
     * — Випадковий секрет, який знає лише цей ПК; наша гра шле його нашому Authority, і той вважає запит запитом власника
     * планети (тунель, через який чужі виглядають як 127.0.0.1, його не підробить).
     */
    public static String ownerSecret() {
        String s = Core.settings.getString("aurora-owner-secret", "");
        if (s.isEmpty()) {
            s = AuroraAuthority.newKey();
            Core.settings.put("aurora-owner-secret", s);
        }
        return s;
    }

    /**
     * What our own Authority needs from this game. Authority asks it on every use, so a password changed while the
     * planet runs takes effect at once.
     * — Те, що нашому Authority потрібно від цієї гри. Authority питає щоразу, тож пароль, змінений під час гри, діє одразу.
     */
    static final AuroraAuthority.Env authorityEnv = new AuroraAuthority.Env() {
        @Override
        public String hostToken() {
            return AuroraNet.hostToken();
        }

        @Override
        public String ownerSecret() {
            return AuroraLink.ownerSecret();
        }

        @Override
        public String ownerUuid() {
            return platform.getUUID();
        }

        @Override
        public boolean planetActive() {
            return campaignSlot == slotAurora;
        }

        @Override
        public boolean advertise() {
            // our sector server answers LAN discovery itself — наш сервер сектора сам відповідає на пошук у LAN
            return planetActive() && !net.server();
        }

        @Override
        public String hostName() {
            return player == null ? "Aurora" : player.name;
        }

        @Override
        public String discoveryDesc() {
            return tr("discovery.desc");
        }

        @Override
        public int gamePort() {
            return Core.settings.getInt("port", port);
        }
    };

    /** Stop our own Authority — Зупинити свій Authority. */
    static void stopAuthority() {
        AuroraAuthority.stop();
        localAuthority = false;
    }

    public static String guestToken() {
        return Core.settings.getString("aurora-token", "");
    }

    /** Address of this PC (our own Authority) — Адреса цього ПК (наш власний Authority). */
    static boolean isThisPc(String base) {
        String h = AuroraWire.host(base);
        return h.startsWith("127.") || h.equals("localhost") || h.equals("::1");
    }

    /**
     * Aurora planets are only reachable on LAN / Tailscale, so only such addresses are probed. A public server never
     * gets our password and can't pretend to be a planet. Off the main thread (may resolve a name).
     * — Планети Aurora доступні лише в LAN / Tailscale, тож перевіряємо лише такі адреси. Публічний сервер ніколи
     * не отримає наш пароль і не зможе прикинутись планетою. Не з головного потоку (може резолвити ім'я).
     */
    /**
     * Requests on this thread carry no password: automatic probes of addresses the player didn't pick right now
     * (the saved server list, an address advertised by a LAN server) never get it.
     * — Запити в цьому потоці не несуть пароля: автоматичні перевірки адрес, які гравець зараз не вибирав
     * (збережений список серверів, адреса з опису сервера в LAN), його ніколи не отримують.
     */
    static final ThreadLocal<Boolean> anonymous = ThreadLocal.withInitial(() -> false);

    static boolean probeable(String base) {
        try {
            java.net.InetAddress a = java.net.InetAddress.getByName(AuroraWire.host(base));
            return a.isLoopbackAddress() || AuroraAuthority.isLan(a) || AuroraAuthority.isTailscale(a);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Finding the planet from a sector — Пошук планети через сектор ----------

    /** Marker in a sector host's server description — Позначка в описі сервера хоста сектора. */
    static final String AUTH_MARK = "Aurora: ";

    /** Authority address a server advertises in its description, or null — Адреса Authority, яку сервер вказує в описі, або null. */
    static String advertised(String desc) {
        if (desc == null) return null;
        int i = desc.lastIndexOf(AUTH_MARK);
        if (i < 0) return null;
        String a = Strings.stripColors(desc.substring(i + AUTH_MARK.length())).trim();
        int sp = a.indexOf(' ');
        if (sp >= 0) a = a.substring(0, sp);
        return a.isEmpty() ? null : AuroraWire.addr(a);
    }

    /** Ping a game server and read the Authority it advertises (blocking, off the main thread) — Пінгнути сервер гри й прочитати Authority, який він вказує (блокує, не з головного потоку). */
    static String advertisedAuthority(String ip, int port) {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        String[] out = {null};
        try {
            net.pingHost(ip, port, h -> {
                out[0] = advertised(h.description);
                latch.countDown();
            }, e -> latch.countDown());
            latch.await(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        return out[0];
    }

    /** Sector id used by Authority: "planet-id" — id сектора для Authority: "планета-номер". */
    /** Cached "planet-id" strings: sid() is called per sector per frame on the maps — Кешовані рядки "планета-id": sid() викликається для кожного сектора щокадру на картах. */
    static final java.util.concurrent.ConcurrentHashMap<mindustry.type.Planet, String[]> sidCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Secret per hosted sector, issued by Authority on claim. Every host-only call carries it, so knowing someone's
     * uuid is not enough to release, overwrite or impersonate their sector.
     * — Секрет на кожен сектор, який хостимо, видає Authority при клеймі. Кожен виклик хоста несе його, тож знати
     * чужий uuid недостатньо, щоб відпустити, перезаписати чи підробити чужий сектор.
     */
    static final java.util.concurrent.ConcurrentHashMap<String, String> hostKeys = new java.util.concurrent.ConcurrentHashMap<>();

    /** Sector we claimed and are still downloading / loading — Сектор, який ми заклеймили й ще завантажуємо. */
    static volatile Sector loadingSector;

    /**
     * While a claimed sector downloads and loads (can take long over Tailscale), tell Authority every 4 s that we are
     * alive, so the 12 s pending claim does not expire and someone else does not take the sector meanwhile.
     * — Поки заклеймлений сектор завантажується (через Tailscale це буває довго), кожні 4 с кажемо Authority, що ми живі,
     * щоб 12-секундний клейм не застарів і сектор тим часом не забрав хтось інший.
     */
    static void startLoadingPings(Sector s) {
        loadingSector = s;
        Timer.schedule(new Timer.Task() {
            int left = 45; // at most 3 minutes — щонайбільше 3 хвилини
            @Override
            public void run() {
                if (loadingSector != s || hostLive || --left <= 0) {
                    cancel();
                    return;
                }
                mainExecutor.submit(() -> {
                    try {
                        post(hostPath(s, "heartbeat") + "&loading=1");
                    } catch (Throwable ignored) {
                    }
                });
            }
        }, 4f, 4f);
    }

    static String sectorPath(Sector s, String action) {
        return "/sector/" + sid(s) + "/" + action;
    }

    /** One request to Authority over the persistent ArcNet link (see AuroraWire) — Один запит до Authority через постійне з'єднання ArcNet (див. AuroraWire). */
    static Res request0(String base, String method, String path, byte[] data, int connectTimeout) throws Exception {
        // Own Authority (this PC) gets the host password, a friend's planet the guest one — Свій Authority (цей ПК) отримує пароль хоста, планета друга — пароль гостя
        String tok = anonymous.get() ? "" : isThisPc(base) ? hostToken() + '\u0001' + ownerSecret() : guestToken();
        byte[] req = AuroraWire.encodeReq(method, path, tok, PROTOCOL, data == null ? new byte[0] : data);
        Object[] r = AuroraWire.decodeResp(AuroraWire.call(base, req, connectTimeout, 15000));
        Res res = new Res();
        res.code = (Integer) r[0];
        res.body = (byte[]) r[1];

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

    static byte[] getBytes(String path) throws Exception {
        Res res = get(path);
        return res.code == 200 ? res.body : null;
    }

    // ---------- Authority watch — Сторож Authority ----------

    /** Does Authority answer? (off the main thread) — Чи відповідає Authority? (не з головного потоку) */
    static boolean pingAuthority(int timeout) {
        try {
            return request(authority(), "GET", "/sectors", null, timeout).code == 200;
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
        showGone(tr("gone.host"));
    }
}
