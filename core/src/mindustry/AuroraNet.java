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
    // 4: Authority over ArcNet instead of HTTP; 5: research opens only through a paid /spend; 6: per-sector host key
    // — 4: Authority через ArcNet замість HTTP; 5: дослідження відкривається лише через оплачений /spend; 6: ключ хоста на сектор
    public static final int PROTOCOL = 6;

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
     * Authority address "host:port": our own (127.0.0.1) on our own planet, the saved planet host address as a guest.
     * Our Authority never overwrites "aurora-authority", so a friend's address is kept.
     * — Адреса Authority: на своїй планеті — свій (127.0.0.1), у гостя — збережена адреса хоста планети.
     * Свій Authority не перезаписує "aurora-authority", тож адреса друга не губиться.
     */
    static String authority() {
        if (planetHost()) return "127.0.0.1:" + AuroraAuthority.port();
        return AuroraWire.addr(Core.settings.getString("aurora-authority", "localhost:" + AuroraWire.DEFAULT_PORT));
    }

    static String authorityHost() {
        return AuroraWire.host(authority());
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

    // ---------- Requests to Authority — Запити до Authority ----------

    /** Claim refused by the planet host (kick / ban), not a network error — Клейм відхилив власник планети (кік / бан), а не помилка мережі. */
    static class RemovedException extends RuntimeException {
        RemovedException(String msg) {
            super(msg);
        }
    }

    static class Res {
        int code;
        byte[] body;
    }

    // ---------- Passwords — Паролі ----------

    /**
     * Two separate passwords: the one guarding OUR planet (host) and the one we send to a FRIEND's planet (guest).
     * They used to be one setting, so typing a friend's password changed ours. Old installs: ours starts as the old value.
     * — Два окремі паролі: той, що захищає НАШУ планету (хост), і той, що ми шлемо на планету ДРУГА (гість).
     * Раніше це було одне налаштування, тож пароль друга змінював наш. Старі встановлення: наш починається зі старого значення.
     */
    public static String hostToken() {
        if (!Core.settings.has("aurora-host-token")) Core.settings.put("aurora-host-token", Core.settings.getString("aurora-token", ""));
        return Core.settings.getString("aurora-host-token", "");
    }

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

    public static void setHostToken(String t) {
        Core.settings.put("aurora-host-token", t == null ? "" : t.trim());
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

    /** Our sector's description + where our planet's Authority is — Опис нашого сектора + де Authority нашої планети. */
    public static String advertise(String desc) {
        String a = planetHost() ? AuroraWire.addr(myIp() + ":" + AuroraAuthority.port()) : authority();
        return (desc == null || desc.isEmpty() ? "" : desc + " ") + AUTH_MARK + a;
    }

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

    /** UI text from the bundles ("aurora." keys; English by default, Ukrainian in bundle_uk_UA) — Текст інтерфейсу з бандлів (ключі "aurora."; за замовчуванням англійська, українська в bundle_uk_UA). */
    public static String tr(String key, Object... args) {
        return args.length == 0 ? Core.bundle.get("aurora." + key) : Core.bundle.format("aurora." + key, args);
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
    /** Cached "planet-id" strings: sid() is called per sector per frame on the maps — Кешовані рядки "планета-id": sid() викликається для кожного сектора щокадру на картах. */
    static final java.util.concurrent.ConcurrentHashMap<mindustry.type.Planet, String[]> sidCache = new java.util.concurrent.ConcurrentHashMap<>();

    static String sid(Sector s) {
        String[] arr = sidCache.get(s.planet);
        if (arr == null || arr.length <= s.id) {
            arr = new String[Math.max(s.planet.sectors.size, s.id + 1)];
            sidCache.put(s.planet, arr);
        }
        String r = arr[s.id];
        if (r == null) arr[s.id] = r = s.planet.name + "-" + s.id;
        return r;
    }

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

    /** sectorPath + who we are + our key for this sector — sectorPath + хто ми + наш ключ до цього сектора. */
    static String hostPath(Sector s, String action) {
        return sectorPath(s, action) + "?player=" + q(me()) + "&key=" + q(hostKeys.getOrDefault(sid(s), ""));
    }

    static String sectorPath(Sector s, String action) {
        return "/sector/" + sid(s) + "/" + action;
    }

    static Res request(String method, String path, byte[] data) throws Exception {
        return request(authority(), method, path, data, 5000);
    }

    /** One retry after 300 ms if the link broke (a reconnect usually helps) — Один повтор через 300 мс, якщо з'єднання обірвалось (перепідключення зазвичай допомагає). */
    static Res request(String base, String method, String path, byte[] data, int connectTimeout) throws Exception {
        try {
            return request0(base, method, path, data, connectTimeout);
        } catch (SocketException e) {
            Thread.sleep(300);
            return request0(base, method, path, data, connectTimeout);
        }
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

    static Res post(String path) throws Exception {
        return request("POST", path, new byte[0]);
    }

    static Res get(String path) throws Exception {
        return request("GET", path, null);
    }

    /** Upload to Authority (POST; request() already retries once) — Відправка в Authority (POST; request() уже має одну повторну спробу). */
    static void putBytes(String path, byte[] data) throws Exception {
        Res res = request("POST", path, data);
        if (res.code != 200) throw new RuntimeException("upload " + path + " -> " + res.code);
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
        String body = "{\"ip\":" + js(myIp()) + ",\"port\":" + myPort() + ",\"player\":" + js(me()) + ",\"name\":" + js(myName())
            + ",\"key\":" + js(hostKeys.getOrDefault(sid(s), "")) + "}";
        Res res = request("POST", sectorPath(s, "claim"), body.getBytes(StandardCharsets.UTF_8));
        if (res.code == 403) {
            // Planet host removed us: banned, or kicked (may come back in N s) — Власник планети нас видалив: бан або кік (можна повернутись через N с)
            boolean banned = false;
            int left = 0;
            try {
                JsonValue v = parse(res);
                banned = v.getBoolean("banned", false);
                left = v.getInt("kicked", 0);
            } catch (Throwable ignored) {
            }
            throw new RemovedException(banned ? tr("removed.banned")
                : left > 0 ? tr("removed.kicked", String.valueOf(left))
                : tr("removed.refused"));
        }
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
        if (c.host) hostKeys.put(sid(s), v.getString("key", ""));
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
        putBytes(hostPath(s, "save"), pack[0]);
        putBytes(hostPath(s, "info"), pack[1]);
    }

    /**
     * Every write of the hosted sector (save, info, stock + ack, release) goes through this one thread, in order:
     * an older snapshot can never overwrite a newer one, and release always comes after the last save.
     * — Кожен запис сектора, який ми хостимо (сейв, info, склад + підтвердження, release), іде через цей один потік, по черзі:
     * старіший знімок ніколи не перезапише новіший, а release завжди йде після останнього сейва.
     */
    static final java.util.concurrent.ExecutorService uploads = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-upload");
        t.setDaemon(true);
        return t;
    });

    /** Queue an upload of a snapshot; on failure the next beat retries — Поставити в чергу заливання знімка; при помилці наступний beat спробує ще раз. */
    static void upload(Sector s, byte[][] pack, String why) {
        uploads.submit(() -> {
            try {
                push(s, pack);
                if (why != null) Log.info("Aurora: sector @ saved to Authority (@)", sid(s), why);
            } catch (Exception e) {
                Log.err("upload failed (" + (why == null ? "beat" : why) + ")", e);
                forcePush = true;
            }
        });
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
            // Planet host's campaign rules before the sector loads — Правила кампанії хоста планети до завантаження сектора
            syncRulesNow();
            try {
                Claim c = claim(sector);

                if (c.wait) {
                    // Host vanished: wait for the check (30 s) and grace time (15 s), then retry — Хост зник: чекаємо перевірку (30 с) і запас (15 с), потім знову
                    Core.app.post(() -> waitForSector(sector, c));
                } else if (c.host) {
                    startLoadingPings(sector);
                    // Pay for the launch now that the sector is ours — Платимо за запуск тепер, коли сектор наш
                    ObjectIntMap<Item> cost = launchCost;
                    if (launchFrom != null && cost != null && cost.size > 0) {
                        int code = spendBlocking(cost);
                        if (code != 200) {
                            release(sector, "launch unpaid");
                            Core.app.post(() -> {
                                clearLaunch();
                                ui.loadfrag.hide();
                                ui.showInfoToast(code == 409 ? tr("launch.noitems") : tr("launch.noconnection"), 4f);
                            });
                            return;
                        }
                        launchCost = null;
                        refreshItems(true);
                    }
                    // 404 = Authority has no such sector (new or abandoned); any other error: don't guess, stop
                    // — 404 = в Authority такого сектора нема (новий або покинутий); будь-яка інша помилка: не вгадуємо, зупиняємось
                    Res sr = get(sectorPath(sector, "save")), ir = get(sectorPath(sector, "info"));
                    if ((sr.code != 200 && sr.code != 404) || (ir.code != 200 && ir.code != 404)) {
                        release(sector, "enter: download failed");
                        throw new RuntimeException("sector download -> " + sr.code + "/" + ir.code);
                    }
                    byte[] save = sr.code == 200 ? sr.body : null;
                    byte[] info = ir.code == 200 ? ir.body : null;
                    boolean none = sr.code == 404;
                    Core.app.post(() -> startHosting(sector, save, info, none));
                } else {
                    // Host on the planet host's PC reports loopback: use the Authority address instead — Хост на ПК хоста планети дає loopback: беремо адресу Authority
                    String ip = (c.ip == null || c.ip.equals("127.0.0.1") || c.ip.equals("localhost")) ? authorityHost() : c.ip;
                    // Only a LAN / Tailscale host: a planet must not be able to send us to an arbitrary internet server
                    // — Лише хост у LAN / Tailscale: планета не повинна мати змогу відправити нас на довільний сервер в інтернеті
                    if (!probeable(ip)) throw new RuntimeException("sector host " + ip + " is not on LAN / Tailscale");
                    if (!retried && !portOpen(ip, c.port, 12)) {
                        Core.app.post(() -> enter(sector, true));
                        return;
                    }
                    Core.app.post(() -> {
                        // Someone else already hosts it: the launch is not needed (and was not paid) — Хтось уже хостить: запуск не потрібен (і не оплачений)
                        clearLaunch();
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
                loadingSector = null;
                Core.app.post(() -> {
                    clearLaunch();
                    ui.loadfrag.hide();
                    if (e instanceof RemovedException) ui.showInfo("[scarlet]Aurora:[] " + e.getMessage());
                    else ui.showException(tr("authority.unavailable"), e);
                });
            }
        });
    }

    static boolean waitCancelled;

    /** Sector locked: show a countdown and retry every ≤3 s, with a cancel button — Сектор закритий: відлік і повтор кожні ≤3 с, з кнопкою скасування. */
    static void waitForSector(Sector sector, Claim c) {
        if (waitCancelled) {
            waitCancelled = false;
            clearLaunch();
            ui.loadfrag.hide();
            return;
        }
        String what = "lost".equals(c.state) ? tr("wait.lost") : tr("wait.updating");
        ui.loadfrag.show(tr("wait.text", what, String.valueOf(c.retry)));
        ui.loadfrag.setButton(() -> {
            waitCancelled = true;
            ui.loadfrag.hide();
        });
        Time.runTask(Math.min(c.retry, 3) * 60f, () -> {
            if (waitCancelled) {
                waitCancelled = false;
                clearLaunch();
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
    /**
     * AURORA: set by a vanilla-style launch (loadout + resources already paid): a brand-new sector is started from this one.
     * — Ставиться ванільним запуском (набір + ресурси вже оплачені): новий сектор стартує з цього.
     */
    public static volatile @Nullable Sector launchFrom;
    /**
     * AURORA: what the pending launch costs. Paid only after Authority made us the host of the new sector,
     * so a launch onto a sector someone else just took costs nothing.
     * — Скільки коштує запланований запуск. Оплачується лише після того, як Authority зробив нас хостом нового сектора,
     * тож запуск на сектор, який щойно зайняв інший, нічого не коштує.
     */
    public static volatile @Nullable ObjectIntMap<Item> launchCost;

    public static void clearLaunch() {
        launchFrom = null;
        launchCost = null;
    }

    static void startHosting(Sector sector, byte[] save, byte[] info, boolean noneOnAuthority) {
        try {
            // Guest: Authority has no save (new or abandoned by the planet host), so our local copy from earlier is stale.
            // Throw it away, or it would bring an abandoned base back. Own planet keeps local data (Authority is ours).
            // — Гість: в Authority сейва нема (новий сектор або покинутий власником), тож наша локальна копія застаріла.
            // Викидаємо її, інакше вона поверне покинуту базу. Своя планета локальне не чіпає (Authority наш).
            if (noneOnAuthority && campaignSlot == slotGuest) {
                if (sector.save != null) sector.save.delete();
                else control.saves.getSectorFile(sector).delete();
                sector.save = null;
                sector.clearInfo();
            }
            if (save != null) {
                control.saves.getSectorFile(sector).writeBytes(save);
                sector.save = null;
                control.saves.load();
            }
            if (info != null) sector.importInfo(new String(info, StandardCharsets.UTF_8));
        } catch (Throwable e) {
            // Throwable, not Exception: broken data from another player (e.g. a stack overflow) must not crash the game
            // — Throwable, а не Exception: зіпсовані дані іншого гравця (напр. переповнення стека) не мають валити гру
            Log.err("restore sector data failed", e);
        }
        current = sector;
        hosting = true;
        wantHost = true;
        ui.loadfrag.hide();
        Sector from = launchFrom;
        clearLaunch();
        // New sector (no save on Authority) launched from a captured one: vanilla loadout and resources — Новий сектор (нема сейву в Authority), запущений із захопленого: ванільний набір і ресурси
        // Also a lost sector (save exists, core destroyed) restarts from the launcher — Також програний сектор (сейв є, ядро знищене) стартує з сектора-запуску
        if (from != null && from != sector && !sector.hasBase()) control.playSector(from, sector);
        else control.playSector(sector);
    }

    /** Server self-check (UDP ping to ourselves) — Самоперевірка сервера (UDP-пінг самого себе). */
    static void selfCheck(String tag, boolean quietOk) {
        net.pingHost("127.0.0.1", myPort(),
            h -> {
                if (!quietOk) ui.showInfoToast(tr("selfcheck.ok", tag), 3f);
            },
            e -> ui.showInfoToast(tr("selfcheck.fail", tag), 8f));
    }

    // ---------- Heartbeat ----------

    static Timer.Task beat;
    /** AURORA: fast stock sync while hosting (every STOCK_EVERY s), separate from the 10 s beat — Швидка синхронізація складу, поки хостимо (кожні STOCK_EVERY с), окремо від 10-секундного beat. */
    static Timer.Task stockTask;
    static final float STOCK_EVERY = 5f;
    /** Generation of the long-poll waiter; bumping it stops the old thread — Покоління waiter'а long poll; збільшення зупиняє старий потік. */
    static volatile int waitGen;

    /**
     * Host: keep a /wait request open at Authority. It returns as soon as someone spends or asks for fresh stock,
     * and then we sync right away. An old Authority without /wait (404) leaves only the periodic sync.
     * — Хост: тримаємо відкритий запит /wait в Authority. Він повертається, щойно хтось витрачає або просить свіжий склад,
     * і тоді ми одразу синхронізуємось. Старий Authority без /wait (404) лишає тільки періодичну синхронізацію.
     */
    static void startWaiter(Sector s) {
        int gen = ++waitGen;
        Thread t = new Thread(() -> {
            long since = -1;
            while (gen == waitGen) {
                try {
                    Res r = get(hostPath(s, "wait") + "&since=" + since);
                    if (gen != waitGen) break;
                    if (r.code == 200) {
                        since = parse(r).getLong("ver", since);
                        Core.app.post(() -> {
                            if (hosting && current == s && !stockBusy) syncStock(s);
                        });
                    } else if (r.code == 404) {
                        break;
                    } else {
                        Thread.sleep(2000);
                    }
                } catch (Throwable e) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }, "aurora-wait");
        t.setDaemon(true);
        t.start();
    }
    /** A stock sync is in flight; skip overlapping ones — Синхронізація складу вже йде; накладені пропускаємо. */
    static volatile boolean stockBusy;
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
        // Instant sync on demand, plus a periodic fallback every 5 s — Миттєва синхронізація на вимогу плюс запасна періодична кожні 5 с
        if (current != null) startWaiter(current);
        stockTask = Timer.schedule(() -> Core.app.post(() -> {
            Sector s = current;
            if (hosting && s != null && !stockBusy) syncStock(s);
        }), STOCK_EVERY, STOCK_EVERY);
        beat = Timer.schedule(() -> Core.app.post(() -> {
            Sector s = current;
            if (!hosting || s == null) return;
            boolean up = ++beats % PUSH_EVERY_BEATS == 0 || forcePush;
            forcePush = false;
            if (beats % 3 == 0) selfCheck("beat", true);
            byte[][] pack = up ? snapshot(s) : null;
            if (pack != null) lastPush = Time.millis();
            boolean reclaim = beatFails > 0;
            byte[] hbBody = heartbeatBody();
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
                    Res hb = request("POST", hostPath(s, "heartbeat"), hbBody);
                    if (hb.code == 403) {
                        // The planet host removed us — Власник планети нас видалив
                        Core.app.post(() -> removedFromPlanet(s));
                        return;
                    }
                    if (hb.code == 404) throw new RuntimeException("heartbeat: not the host anymore");
                    if (hb.code == 200) applyKicks(hb);
                    if (pack != null) upload(s, pack, null); // ordered with every other write — по черзі з усіма іншими записами
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
        waitGen++;
        if (beat != null) {
            beat.cancel();
            beat = null;
        }
        if (stockTask != null) {
            stockTask.cancel();
            stockTask = null;
        }
    }

    static void onBeat(boolean ok) {
        if (ok) {
            if (beatFails >= 3) ui.showInfoToast(tr("connection.restored"), 4f);
            beatFails = 0;
        } else {
            forcePush = true;
            if (++beatFails == 3) {
                ui.showInfoToast(tr("connection.lost"), 8f);
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
        ui.showInfoToast(tr("sector.taken"), 7f);
        Time.runTask(60f, () -> {
            if (!hosting && !asClient && auroraSlot()) enter(s);
        });
    }

    /**
     * Our sector's players for Authority: [{uuid, ip, name, pid}], ourselves included (ip empty, Authority knows it).
     * pid = entity id, so the planet host can point at a player he sees here. Main thread.
     * — Гравці нашого сектора для Authority: [{uuid, ip, name, pid}], разом із нами (ip порожній, Authority його знає).
     * pid = id сутності, щоб власник планети міг вказати на гравця, якого тут бачить. Головний потік.
     */
    static byte[] heartbeatBody() {
        StringBuilder sb = new StringBuilder("[");
        Groups.player.each(p -> {
            if (!p.isLocal() && p.con == null) return;
            if (sb.length() > 1) sb.append(',');
            String uuid = p.isLocal() ? me() : p.con.uuid, ip = p.isLocal() ? "" : plainIp(p.con.address);
            sb.append("{\"uuid\":").append(js(uuid)).append(",\"ip\":").append(js(ip))
                .append(",\"name\":").append(js(Strings.stripColors(p.name))).append(",\"pid\":").append(p.id).append('}');
        });
        return sb.append(']').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Heartbeat answer: {"kick":[uuid...]} — players the planet host removed — Відповідь heartbeat: гравці, яких видалив власник планети. */
    static void applyKicks(Res hb) {
        if (hb.body == null || hb.body.length == 0) return;
        ObjectSet<String> out = new ObjectSet<>();
        try {
            JsonValue k = parse(hb).get("kick");
            for (JsonValue c = k == null ? null : k.child; c != null; c = c.next) out.add(c.asString());
        } catch (Throwable ignored) {
            return;
        }
        if (out.isEmpty()) return;
        Core.app.post(() -> Groups.player.each(p -> {
            if (!p.isLocal() && p.con != null && out.contains(p.con.uuid)) {
                Log.info("Aurora: removing @ (planet host's decision)", p.plainName());
                p.kick(mindustry.net.Packets.KickReason.kick);
            }
        }));
    }

    /** The planet host removed us while we host a sector: close it, save nothing, back to the menu — Власник планети видалив нас, поки ми хостимо сектор: закрити, нічого не зберігати, в меню. */
    static void removedFromPlanet(Sector s) {
        if (!hosting || current != s) return;
        Log.warn("Aurora: removed from the planet by its host");
        hosting = false;
        hostLive = false;
        wantHost = false;
        stopBeat();
        current = null;
        if (net.server()) net.closeServer();
        logic.reset();
        ui.showInfo(tr("removed.fromplanet"));
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
            upload(s, pack, why);
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
        // Same queue as every other write, we only wait for it — Та сама черга, що й для всіх записів, лише чекаємо на неї
        var f = uploads.submit(() -> {
            try {
                push(s, pack);
                Log.info("Aurora: sector @ saved on pause", sid(s));
            } catch (Exception e) {
                Log.err("pause push failed", e);
                forcePush = true;
            }
        });
        try {
            f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
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
        ui.loadfrag.show(tr("saving"));
        // After everything already queued: the last save lands before release — Після всього, що вже в черзі: останній сейв приходить до release
        uploads.submit(() -> {
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
            post(hostPath(s, "release"));
        } catch (Throwable t) {
            Log.err(why + ": release failed", t);
        }
    }

    /** Once: snapshot -> upload -> release -> stop Authority — Один раз: знімок -> заливання -> release -> зупинка Authority. */
    /**
     * mainThread: called from DisposeEvent (normal exit), a snapshot of the world is safe. From the JVM shutdown hook
     * (killed / closed abruptly) the world may be half-updated on another thread, so no snapshot: only release and stop.
     * — mainThread: виклик з DisposeEvent (звичайний вихід), знімок світу безпечний. З хука вимкнення JVM
     * (гру вбили / різко закрили) світ може бути наполовину оновлений іншим потоком, тож без знімка: лише release і зупинка.
     */
    static synchronized void shutdownWork(boolean mainThread) {
        if (shutdownDone) return;
        shutdownDone = true;
        Sector s = current;
        if (hosting && s != null) {
            byte[][] pack = null;
            if (mainThread) {
                try {
                    pack = snapshot(s);
                } catch (Throwable t) {
                    Log.err("shutdown: snapshot failed", t);
                }
            }
            byte[][] fp = pack;
            // Through the upload queue (after any queued save), wait a few seconds at most — Через чергу заливань (після вже поставлених), чекаємо щонайбільше кілька секунд
            var f = uploads.submit(() -> {
                try {
                    if (fp != null) push(s, fp);
                } catch (Throwable t) {
                    Log.err("shutdown: push failed", t);
                }
                release(s, "shutdown");
            });
            try {
                f.get(8, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.err("shutdown: upload queue did not finish in time", e);
            }
        }
        if (localAuthority) AuroraAuthority.stop(); // snapshot/release first, then stop — спершу знімок/release, потім stop
        AuroraWire.closeAll();
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
        ui.showInfoToast(tr("host.left"), 4f);
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
                showGone(tr("gone.authority"));
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
        // Any slot change (own planet <-> guest <-> normal): forget everything that belonged to the previous planet,
        // so its map frames, stock, owner and bans don't show up on the new one until the next refresh
        // — Будь-яка зміна слоту (своя планета <-> гість <-> звичайна): забуваємо все, що належало попередній планеті,
        // щоб її рамки на карті, склад, власник і бани не з'являлись на новій до наступного оновлення
        guestSession = false;
        watchFails = 0;
        remoteBases = new ObjectSet<>();
        liveSectors = new ObjectSet<>();
        remoteState = new ObjectIntMap<>();
        shared = new ItemSeq();
        ownerHash = "";
        ownerIps = new ObjectSet<>();
        banUuids = new ObjectSet<>();
        banIps = new ObjectSet<>();
        refusedUnlocks.clear();
        blendValue.clear();
        blendTime.clear();
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
        float[] w = weights(sec, base, waves, attack);
        int o = sec.id * 3;
        float blue = w[o], capture = w[o + 1], atk = w[o + 2];
        if (blue <= 0f && capture <= 0f && atk <= 0f) return;
        Color c = Tmp.c1;

        // Every state fades in / out over FADE_TICKS, so switching between them is smooth — Кожен стан плавно з'являється / зникає за FADE_TICKS, тож перемикання між ними плавне
        if (atk > 0f) {
            // Attack: fast red blinking, red edges, red runner — Атака: швидке червоне блимання, червоні краї, червоний бігунок
            float pulse = Mathf.absin(Time.time, 7f, 1f);
            planets.fill(sec, c.set(attackColor).a(alpha * atk * (0.15f + 0.35f * pulse)), -0.001f);
            planets.drawBorders(sec, attackColor, alpha * atk * (0.5f + 0.5f * pulse));
            drawRunner(planets, sec, alpha * atk, attackColor);
        }
        if (capture > 0f) {
            // Not captured yet (waves still running): calm red fill breathing once per second + red runner
            // — Ще не захоплений (хвилі ще йдуть): спокійна червона заливка, що "дихає" раз на секунду, + червоний бігунок
            float breath = 0.5f + 0.5f * Mathf.sin((float) (Time.globalTime / 60.0 * Mathf.PI2));
            planets.fill(sec, c.set(attackColor).a(alpha * capture * (0.08f + 0.22f * breath)), -0.001f);
            drawRunner(planets, sec, alpha * capture, attackColor);
        }
        if (blue > 0f) {
            // Captured base with a player: blue runner (the frame itself turns blue in frameColor) — Захоплена база з гравцем: синій бігунок (сама рамка синіє в frameColor)
            drawRunner(planets, sec, alpha * blue, allyColor);
        }
    }

    /** State fade time, ticks (0.5 s) — Час переходу між станами, тіки (0.5 с). */
    static final float FADE_TICKS = 30f;
    /** Per planet: 3 weights per sector (blue, capturing, attack) and the last update time — На планету: 3 ваги на сектор (синій, захоплення, атака) і час останнього оновлення. */
    static final ObjectMap<mindustry.type.Planet, float[]> blendValue = new ObjectMap<>();
    static final ObjectMap<mindustry.type.Planet, double[]> blendTime = new ObjectMap<>();
    static final Color frameTmp = new Color();

    /** Sector not fully captured yet: no base, or waves still running — Сектор ще не повністю захоплений: бази нема, або хвилі ще йдуть. */
    public static boolean capturing(boolean base, boolean waves) {
        return !base || waves;
    }

    /**
     * Moves the three state weights of a sector toward their targets and returns the planet's weight array
     * (index sector.id * 3 + 0 blue, 1 capturing, 2 attack). Exactly one target is 1 while someone plays, none otherwise.
     * The first call for a sector (map just opened) jumps straight to the targets. Main thread only.
     * — Рухає три ваги станів сектора до цілей і повертає масив ваг планети
     * (індекс sector.id * 3 + 0 синій, 1 захоплення, 2 атака). Поки хтось грає, рівно одна ціль = 1, інакше жодна.
     * Перший виклик для сектора (карту щойно відкрили) одразу стає цільовим. Лише головний потік.
     */
    static float[] weights(Sector sec, boolean base, boolean waves, boolean attack) {
        float[] v = blendValue.get(sec.planet);
        double[] last = blendTime.get(sec.planet);
        if (v == null || v.length < (sec.id + 1) * 3) {
            int size = Math.max(sec.planet.sectors.size, sec.id + 1);
            blendValue.put(sec.planet, v = new float[size * 3]);
            blendTime.put(sec.planet, last = new double[size]);
        }
        double now = Time.globalTime;
        float dt = (float) (now - last[sec.id]);
        if (last[sec.id] == 0 || dt < 0 || dt > 120f) dt = FADE_TICKS; // first look / long gap: no fade — перший погляд / довга перерва: без переходу
        last[sec.id] = now;

        boolean live = liveSector(sec);
        boolean atk = live && (attack || (!base && sec.hasEnemyBase()));
        boolean cap = live && !atk && capturing(base, waves);
        boolean blue = live && !atk && !cap;
        float step = dt / FADE_TICKS;
        int o = sec.id * 3;
        v[o] = Mathf.approach(v[o], blue ? 1f : 0f, step);
        v[o + 1] = Mathf.approach(v[o + 1], cap ? 1f : 0f, step);
        v[o + 2] = Mathf.approach(v[o + 2], atk ? 1f : 0f, step);
        return v;
    }

    static final arc.math.geom.Vec3 runCenter = new arc.math.geom.Vec3(), runO1 = new arc.math.geom.Vec3(), runO2 = new arc.math.geom.Vec3(),
        runI1 = new arc.math.geom.Vec3(), runI2 = new arc.math.geom.Vec3(), runA0 = new arc.math.geom.Vec3(), runA1 = new arc.math.geom.Vec3(),
        runB0 = new arc.math.geom.Vec3(), runB1 = new arc.math.geom.Vec3();
    /** Runner speed (edges per second), tail length (edges), slices per edge — Швидкість бігунка (ребер за секунду), довжина хвоста (ребер), шматків на ребро. */
    static final float RUN_SPEED = 1.5f, RUN_TAIL = 1.6f;
    static final int RUN_SLICES = 6;

    /**
     * A bright light running along the sector frame, with a fading tail. Drawn a hair above the frame so it is not hidden by it.
     * Main thread only (shared temp vectors).
     * — Яскравий вогник, що біжить по рамці сектора, з хвостом, що згасає. Малюється трохи вище рамки, щоб вона його не ховала.
     * Лише головний потік (спільні тимчасові вектори).
     */
    static final Color runBase = new Color();

    static void drawRunner(PlanetRenderer planets, Sector sec, float alpha, Color color) {
        runBase.set(color);
        var corners = sec.tile.corners;
        int n = corners.length;
        runArad = (PlanetRenderer.outlineRad + 0.0005f) * sec.planet.radius;
        runCenter.set(sec.tile.v).scl(runArad);
        // globalTime keeps running while the game is paused on the planet map — globalTime іде далі, навіть коли гра на паузі на карті планети
        float head = (float) ((Time.globalTime / 60.0 * RUN_SPEED) % n);
        float tail = head - RUN_TAIL;
        // The lit stretch [tail, head] along the frame; if it wraps past corner 0, draw its end piece too
        // — Освітлений відрізок [tail, head] вздовж рамки; якщо він переходить через кут 0, малюємо і його кінець
        runSpan(planets, sec, corners, n, Math.max(tail, 0f), head, 0f, tail, alpha);
        if (tail < 0f) runSpan(planets, sec, corners, n, n + tail, n, n, tail, alpha);
        if (planets.batch.getNumVertices() >= planets.batch.getMaxVertices() - 6 * 6 * RUN_SLICES) planets.batch.flush(arc.graphics.Gl.triangles);
    }

    static float runArad;

    /** Draws frame positions [s, e] (in edges); off maps them back to the tail..head scale — Малює позиції рамки [s, e] (у ребрах); off переводить їх назад у шкалу tail..head. */
    static void runSpan(PlanetRenderer planets, Sector sec, PlanetGrid.Corner[] corners, int n, float s, float e, float off, float tail, float alpha) {
        if (e <= s) return;
        float stroke = 0.03f;
        for (int i = (int) Math.floor(s); i < Math.ceil(e) && i < n; i++) {
            float a = Math.max(s, i), b = Math.min(e, i + 1);
            if (b <= a) continue;
            runO1.set(corners[i].v).scl(runArad);
            runO2.set(corners[(i + 1) % n].v).scl(runArad);
            runI1.set(runO1).sub(runCenter).setLength(runO1.dst(runCenter) - stroke).add(runCenter);
            runI2.set(runO2).sub(runCenter).setLength(runO2.dst(runCenter) - stroke).add(runCenter);
            for (int k = 0; k < RUN_SLICES; k++) {
                float x0 = a + (b - a) * k / RUN_SLICES, x1 = a + (b - a) * (k + 1) / RUN_SLICES;
                runA0.set(runO1).lerp(runO2, x0 - i);
                runA1.set(runO1).lerp(runO2, x1 - i);
                runB0.set(runI1).lerp(runI2, x0 - i);
                runB1.set(runI1).lerp(runI2, x1 - i);
                // Brightness grows smoothly from the tail (0) to the head (1), per vertex — Яскравість плавно росте від хвоста (0) до голови (1), для кожної вершини
                Color c0 = runColor(runC0, (x0 - off - tail) / RUN_TAIL, alpha), c1 = runColor(runC1, (x1 - off - tail) / RUN_TAIL, alpha);
                var batch = planets.batch;
                batch.vertex(runA0, c0);
                batch.vertex(runA1, c1);
                batch.vertex(runB0, c0);
                batch.vertex(runB0, c0);
                batch.vertex(runA1, c1);
                batch.vertex(runB1, c1);
            }
        }
    }

    static final Color runC0 = new Color(), runC1 = new Color();

    static Color runColor(Color out, float f, float alpha) {
        f = Mathf.clamp(f);
        return out.set(runBase).lerp(Color.white, 0.6f).a(alpha * f * f * (3f - 2f * f));
    }

    /**
     * Frame color of a base: blue while someone plays there (and it is not under attack), orange otherwise.
     * — Колір рамки бази: синій, поки там хтось грає (і нема атаки), інакше помаранчевий.
     */
    public static Color frameColor(Sector sec, boolean attack, boolean waves) {
        // Smooth orange <-> blue; stays orange while the sector is still being captured — Плавно помаранчевий <-> синій; лишається помаранчевим, поки сектор ще захоплюють
        return frameTmp.set(baseColor).lerp(allyColor, weights(sec, true, waves, attack)[sec.id * 3]);
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
                        ui.hudfrag.showToast(new arc.scene.style.TextureRegionDrawable(u.uiIcon), tr("researched", who, u.localizedName));
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
                if (state.isGame()) ui.hudfrag.showToast(Icon.book, tr("researched.bulk", String.valueOf(fresh.size)));
            }
        } finally {
            applyingRemote = false;
        }
    }

    /** Unlocks Authority refused (research never paid for); not sent again this session — Дослідження, які Authority відхилив (не оплачені); більше не шлемо в цій сесії. */
    static final java.util.Set<String> refusedUnlocks = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static void pushUnlock(String name, String by) throws Exception {
        if (refusedUnlocks.contains(name)) return;
        Res r = get("/unlock/" + q(name) + "?by=" + q(by));
        if (r.code == 403 || r.code == 400) refusedUnlocks.add(name);
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
                        } else if (u.unlocked() && !u.alwaysUnlocked && !refusedUnlocks.contains(u.name)) {
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

    // ---------- Shared campaign rules — Спільні правила кампанії ----------

    /** A guest plays on someone else's planet: campaign rules come from that planet's host — Гість грає на чужій планеті: правила кампанії приходять від хоста планети. */
    public static boolean rulesFromHost() {
        return campaignSlot == slotGuest;
    }

    /** Pulls the planet host's campaign rules (guest only) and applies them — Тягне правила кампанії хоста планети (лише гість) і застосовує їх. */
    public static void syncRules() {
        if (!rulesFromHost()) return;
        mainExecutor.submit(AuroraNet::syncRulesNow);
    }

    /** Blocking fetch, result applied on the main thread — Блокуючий запит, результат застосовується в головному потоці. */
    static void syncRulesNow() {
        if (!rulesFromHost()) return;
        try {
            Res res = get("/rules");
            if (res.code != 200) return; // old Authority without /rules — старий Authority без /rules
            JsonValue root = parse(res);
            Core.app.post(() -> applyRules(root));
        } catch (Throwable t) {
            Log.err("syncRules failed", t);
        }
    }

    static void applyRules(JsonValue root) {
        if (!rulesFromHost()) return;
        for (JsonValue c = root.child; c != null; c = c.next) {
            mindustry.type.Planet pl = content.planet(c.name);
            if (pl == null) continue;
            mindustry.game.CampaignRules remote;
            try {
                remote = mindustry.io.JsonIO.readRemote(mindustry.game.CampaignRules.class, c); // no class tags — без тегів класів
            } catch (Throwable ex) {
                continue; // unknown fields / values (other version) — невідомі поля / значення (інша версія)
            }
            if (remote == null || remote.difficulty == null) continue;
            String was = mindustry.io.JsonIO.write(pl.campaignRules);
            if (was.equals(mindustry.io.JsonIO.write(remote))) continue;
            Log.info("Aurora: @ campaign rules from planet host (difficulty @)", pl.name, remote.difficulty);
            // Copy into the same object: an open rules dialog keeps showing it — Копіюємо в той самий об'єкт: відкритий діалог правил і далі його показує
            mindustry.io.JsonIO.copy(remote, pl.campaignRules);
            pl.saveRules();
            // We host a sector of this planet: apply now and tell our clients — Ми хостимо сектор цієї планети: застосувати зараз і сказати клієнтам
            if (state.isGame() && state.isCampaign() && state.getPlanet() == pl && !net.client()) {
                pl.campaignRules.apply(pl, state.rules);
                if (net.server()) Call.setRules(state.rules);
            }
        }
    }

    // ---------- Removing players: only the planet host — Видалення гравців: лише хост планети ----------

    /** SHA-256 of the planet host's uuid (Authority never gives the uuid itself) — SHA-256 uuid власника планети (сам uuid Authority не віддає). */
    static volatile String ownerHash = "";
    static volatile ObjectSet<String> ownerIps = new ObjectSet<>(), banUuids = new ObjectSet<>(), banIps = new ObjectSet<>();

    /** Dev console (JS): not as a guest on someone else's planet (own planet and normal campaign are fine) — Консоль розробника (JS): не гостем на чужій планеті (своя планета й звичайна кампанія — можна). */
    public static boolean consoleAllowed() {
        return campaignSlot != slotGuest;
    }

    /** Abandoning a sector: in Aurora only the planet host — Покинути сектор: в Aurora лише власник планети. */
    public static boolean mayAbandon() {
        return !auroraSlot() || planetHost();
    }

    /** Planet host abandons a sector nobody plays (Authority side). False if someone plays it — Власник планети покидає сектор, де ніхто не грає. false, якщо там хтось грає. */
    public static boolean abandonOnPlanet(Sector s) {
        if (!planetHost()) return false;
        boolean ok = AuroraAuthority.abandon(sid(s));
        if (ok) refreshMap();
        return ok;
    }

    /** Any Aurora session, for UI outside this class — Будь-яка сесія Aurora, для UI поза цим класом. */
    public static boolean sessionActivePublic() {
        return sessionActive();
    }

    /** We host an Aurora sector — Ми хостимо сектор Aurora. */
    public static boolean hostingAurora() {
        return sessionActive() && net.server();
    }

    static String plainIp(String ip) {
        if (ip == null) return "";
        if (ip.startsWith("/")) ip = ip.substring(1);
        if (ip.startsWith("::ffff:")) ip = ip.substring(7);
        return ip;
    }

    /**
     * Is this player the planet host: we are him, or his uuid and IP match what Authority reports (uuid alone could be copied)
     * — Чи цей гравець хост планети: ми — це він, або його uuid і IP збігаються з тим, що каже Authority (самий uuid можна скопіювати).
     */
    public static boolean isPlanetOwner(Player p) {
        if (p == null) return false;
        if (p.isLocal()) return planetHost();
        if (p.con == null || ownerHash.isEmpty()) return false;
        return ownerHash.equals(AuroraAuthority.sha256(p.con.uuid)) && ownerIps.contains(plainIp(p.con.address));
    }

    /**
     * Server side: may this player kick / ban here. In Aurora only the planet host playing locally; as a client he removes
     * players through his own Authority (removePlayer), never through the sector host.
     * — На сервері: чи може цей гравець тут кікати / банити. В Aurora лише власник планети, що грає локально; як клієнт
     * він видаляє гравців через свій Authority (removePlayer), а не через хоста сектора.
     */
    public static boolean mayRemove(Player p) {
        return !hostingAurora() || (p != null && p.isLocal() && planetHost());
    }

    /** UI: show kick / ban buttons — UI: показувати кнопки кіку / бану. */
    public static boolean canRemovePlayers() {
        return !sessionActive() || planetHost();
    }

    /**
     * Kick / ban button in the player list. Vanilla or our own sector: the usual admin request. Planet host as a client in
     * someone's sector: straight into his own Authority (the sector host's next heartbeat throws the player out, <= 10 s).
     * — Кнопка кіку / бану в списку гравців. Ваніла або свій сектор: звичайний адмін-запит. Власник планети клієнтом у чужому
     * секторі: напряму у свій Authority (наступний heartbeat хоста сектора викине гравця, <= 10 с).
     */
    public static void removePlayer(Player user, boolean ban) {
        if (!sessionActive() || !net.client()) {
            Call.adminRequest(user, ban ? mindustry.net.Packets.AdminAction.ban : mindustry.net.Packets.AdminAction.kick, null);
            return;
        }
        Sector s = current;
        if (!planetHost() || s == null) {
            ui.showInfoToast(tr("remove.owneronly"), 4f);
            return;
        }
        String name = AuroraAuthority.removeInSector(sid(s), user.id, ban);
        if (name == null) ui.showInfoToast(tr("remove.unknown"), 5f);
        else ui.showInfoToast(tr(ban ? "remove.banned" : "remove.kicked", name), 5f);
    }

    static boolean planetBanned(Player p) {
        if (p == null || p.con == null) return false;
        return banUuids.contains(p.con.uuid) || banIps.contains(plainIp(p.con.address));
    }

    /** Planet-wide ban from our own sector: we are the planet host (mayRemove checked it) — Блок на всю планету з нашого сектора: ми власник планети (перевірив mayRemove). */
    public static void planetBan(Player other) {
        if (other == null || other.con == null || !planetHost()) return;
        String uuid = other.con.uuid, ip = plainIp(other.con.address);
        banUuids.add(uuid);
        if (!ip.isEmpty()) banIps.add(ip);
        AuroraAuthority.ban(uuid, ip, Strings.stripColors(other.name), "planet host");
    }

    /** Sector host: who is the planet host, who is banned; give admin / kick accordingly — Хост сектора: хто хост планети, хто заблокований; дати адмінку / кікнути. */
    static void syncAdmin() {
        if (!hostingAurora()) return;
        mainExecutor.submit(() -> {
            try {
                Res o = get("/owner"), b = get("/bans");
                if (o.code != 200 && b.code != 200) return; // old Authority — старий Authority
                String uuid = ownerHash;
                ObjectSet<String> ips = ownerIps, bu = banUuids, bi = banIps;
                if (o.code == 200) {
                    JsonValue j = parse(o);
                    uuid = j.getString("uuidHash", "");
                    ips = new ObjectSet<>();
                    for (JsonValue c = j.get("ips") == null ? null : j.get("ips").child; c != null; c = c.next) ips.add(c.asString());
                }
                if (b.code == 200) {
                    JsonValue j = parse(b);
                    bu = new ObjectSet<>();
                    bi = new ObjectSet<>();
                    for (JsonValue c = j.get("uuids") == null ? null : j.get("uuids").child; c != null; c = c.next) bu.add(c.asString());
                    for (JsonValue c = j.get("ips") == null ? null : j.get("ips").child; c != null; c = c.next) bi.add(c.asString());
                }
                String fu = uuid;
                ObjectSet<String> fi = ips, fbu = bu, fbi = bi;
                Core.app.post(() -> {
                    ownerHash = fu;
                    ownerIps = fi;
                    banUuids = fbu;
                    banIps = fbi;
                    if (hostingAurora()) Groups.player.each(AuroraNet::checkPlayer);
                });
            } catch (Throwable t) {
                Log.err("syncAdmin failed", t);
            }
        });
    }

    /** Kick a banned player, give the planet host admin — Кікнути заблокованого, дати хосту планети адмінку. */
    static void checkPlayer(Player p) {
        if (p.isLocal() || p.con == null) return;
        if (planetBanned(p) && !isPlanetOwner(p)) {
            p.kick(mindustry.net.Packets.KickReason.banned);
        } else if (isPlanetOwner(p) && !p.admin) {
            p.admin = true;
        }
    }

    // ---------- Shared items — Спільні ресурси ----------

    static volatile ItemSeq shared = new ItemSeq();

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
    /**
     * Connect timeout for "is this an Aurora planet?" checks. ArcNet needs a TCP connect plus a registration round trip,
     * which over a Tailscale relay from a phone can take longer than the old 2 s.
     * — Тайм-аут підключення для перевірок "чи це планета Aurora?". ArcNet потребує TCP-підключення плюс обмін реєстрацією,
     * а через ретранслятор Tailscale з телефона це буває довше за старі 2 с.
     */
    static final int PROBE_TIMEOUT = 6000;

    public static void probePlanet(String ip, Cons<String> done) {
        String base = AuroraWire.addr(ip.indexOf(':') >= 0 && !ip.startsWith("[") ? "[" + ip + "]" : ip);
        mainExecutor.submit(() -> {
            if (!probeable(base)) {
                Core.app.post(() -> done.get(null)); // public address: not an Aurora planet — публічна адреса: не планета Aurora
                return;
            }
            String text = null;
            quietProto.set(true);
            anonymous.set(true); // the server list probes on its own: no password — список серверів перевіряє сам: без пароля
            try {
                Res mode = request(base, "GET", "/mode", null, PROBE_TIMEOUT);
                int theirs = mode.code == 426 ? remoteProtocol(base) : PROTOCOL;
                if (mode.code == 401) {
                    text = tr("probe.password");
                } else if (mode.code == 426) {
                    text = tr("probe.version", theirs > 0 ? String.valueOf(theirs) : tr("probe.version.old"), String.valueOf(PROTOCOL));
                } else {
                    if (mode.code == 200 && parse(mode).getBoolean("active", false)) {
                        int live = 0;
                        Res s = request(base, "GET", "/sectors", null, PROBE_TIMEOUT);
                        if (s.code == 200) for (JsonValue c = parse(s).child; c != null; c = c.next) live++;
                        text = tr("probe.open", live == 0 ? tr("probe.nobody") : tr("probe.playing", String.valueOf(live)));
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                quietProto.set(false);
                anonymous.set(false);
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
        String direct = AuroraWire.addr(ip.indexOf(':') >= 0 && !ip.startsWith("[") ? "[" + ip + "]" : ip);
        mainExecutor.submit(() -> {
            if (!probeable(direct)) {
                Core.app.post(() -> done.get(false)); // public server: a normal game — публічний сервер: звичайна гра
                return;
            }
            String found = null;

            try {
                Res mode = request(direct, "GET", "/mode", null, PROBE_TIMEOUT);
                if (mode.code == 200 && parse(mode).getBoolean("active", false)) {
                    found = direct;
                } else if (mode.code == 200 || mode.code == 404) {
                    Res res = request(direct, "GET", "/sectors", null, PROBE_TIMEOUT);
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
                        Res res = request(saved, "GET", "/sectors", null, PROBE_TIMEOUT);
                        if (res.code == 200) {
                            String authHost = AuroraWire.host(saved);
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

            // Still unknown: the sector host says in its server info where its planet is — Досі невідомо: хост сектора каже в інформації про сервер, де його планета
            if (found == null) {
                String adv = advertisedAuthority(ip, port);
                if (adv != null && probeable(adv)) {
                    try {
                        // It must really be an Aurora Authority (the host may list a Tailscale IP while we found it on LAN,
                        // so its own address can't be matched) — Має бути справжній Authority Aurora (хост може вказувати
                        // Tailscale-IP, а ми знайшли його в LAN, тож його адресу не звірити)
                        // Without our password: the address came from someone else's server description; 401 still means Aurora
                        // — Без нашого пароля: адреса прийшла з опису чужого сервера; 401 теж означає Aurora
                        anonymous.set(true);
                        Res res;
                        try {
                            res = request(adv, "GET", "/sectors", null, PROBE_TIMEOUT);
                        } finally {
                            anonymous.set(false);
                        }
                        if (res.code == 200 || res.code == 401) found = adv;
                    } catch (Throwable ignored) {
                    }
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
                syncRules();
                syncAdmin();
                refreshItems();
                refreshMap();
            }
        }, 5f, 5f);
        // While the research tree is open, refresh the shared stock every 2 s — Поки відкрите дерево досліджень, оновлюємо спільний склад кожні 2 с
        Timer.schedule(() -> Core.app.post(() -> {
            // Plain refresh: "fresh" (waking every host) only on open and after a spend — Звичайне оновлення: "fresh" (будить усіх хостів) лише при відкритті й після витрати
            if (sessionActive() && ui != null && ui.research != null && ui.research.isShown()) refreshItems(false);
        }), 2f, 2f);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownWork(false), "aurora-shutdown"));
        Events.on(DisposeEvent.class, e -> shutdownWork(true));

        // Upload the sector on important events — Заливати сектор при важливих подіях
        Events.on(WaveEvent.class, e -> pushNow(false, "wave"));
        Events.on(SectorCaptureEvent.class, e -> pushNow(true, "capture"));
        Events.on(SectorLaunchEvent.class, e -> pushNow(true, "launch"));
        // Lost sector: upload the lost state, or the next host would load the old living base — Програний сектор: заливаємо стан програшу, інакше наступний хост завантажить стару живу базу
        Events.on(SectorLoseEvent.class, e -> pushNow(true, "lose"));
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

        // Joined our Aurora sector: banned -> out, planet host -> admin — Зайшов у наш сектор Aurora: заблокований -> геть, хост планети -> адмін
        Events.on(PlayerJoin.class, e -> {
            if (hostingAurora()) checkPlayer(e.player);
        });

        // The sector we claimed has loaded: open our server — Сектор, який ми заклеймили, завантажився: відкриваємо свій сервер
        Events.on(WorldLoadEvent.class, e -> {
            if (!wantHost) return;
            wantHost = false;
            try {
                net.host(myPort());
                player.admin = true;
                syncAdmin();
                Events.fire(new HostEvent());
                startBeat();
                Sector hs = current;
                if (hs != null) mainExecutor.submit(() -> {
                    try {
                        post(hostPath(hs, "heartbeat"));
                    } catch (Exception ex) {
                        Log.err("first heartbeat failed", ex);
                    }
                });
                hostLive = true;
                loadingSector = null;
                Time.runTask(60f, () -> {
                    if (hosting && current == hs && !stockBusy) syncStock(hs);
                });
                Time.runTask(120f, () -> selfCheck(tr("selfcheck.start"), false));
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
