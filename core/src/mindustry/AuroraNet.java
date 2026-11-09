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
import static mindustry.AuroraAdmin.banIps;
import static mindustry.AuroraAdmin.banUuids;
import static mindustry.AuroraAdmin.checkPlayer;
import static mindustry.AuroraAdmin.ownerHash;
import static mindustry.AuroraAdmin.ownerIps;
import static mindustry.AuroraAdmin.plainIp;
import static mindustry.AuroraAdmin.syncAdmin;
import static mindustry.AuroraLink.AUTH_MARK;
import static mindustry.AuroraLink.authorityEnv;
import static mindustry.AuroraLink.hostKeys;
import static mindustry.AuroraLink.loadingSector;
import static mindustry.AuroraLink.pingAuthority;
import static mindustry.AuroraLink.request0;
import static mindustry.AuroraLink.sectorPath;
import static mindustry.AuroraLink.sidCache;
import static mindustry.AuroraLink.startWatch;
import static mindustry.AuroraSectors.endClient;
import static mindustry.AuroraSectors.pushBlocking;
import static mindustry.AuroraSectors.pushNow;
import static mindustry.AuroraSectors.release;
import static mindustry.AuroraSectors.returnFromSector;
import static mindustry.AuroraSectors.selfCheck;
import static mindustry.AuroraSectors.shutdownWork;
import static mindustry.AuroraSectors.startBeat;
import static mindustry.AuroraSectors.stopBeat;

/**
 * Aurora client: talks to the planet's Authority — sector claim, heartbeat, save upload/download,
 * shared research and items, planet map state.
 * — Клієнт Aurora: розмова з Authority планети — клейм сектора, heartbeat, заливання/завантаження сейвів,
 * спільні дослідження й ресурси, стан карти планети.
 *
 * This class is the public face of the Aurora client (session state and what the game calls); the internals live in
 * AuroraLink, AuroraSectors, AuroraAdmin and AuroraJoin.
 * — Цей клас — публічне обличчя клієнта Aurora (стан сесії і те, що викликає гра); нутрощі живуть в
 * AuroraLink, AuroraSectors, AuroraAdmin і AuroraJoin.
 */
public class AuroraNet {

    // ---------- Protocol version — Версія протоколу ----------

    /** Protocol version, shared with Authority: see AuroraWire.PROTOCOL — Версія протоколу, спільна з Authority: див. AuroraWire.PROTOCOL. */
    public static final int PROTOCOL = AuroraWire.PROTOCOL;

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
                    // 169.254.x: a dead adapter (no cable / no DHCP), nobody can reach it — мертвий адаптер (без кабелю / DHCP), до нього ніхто не достукається
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip.startsWith("100.")) return ip; // Tailscale
                    if (fallback == null && !ip.startsWith("192.168.56.")) fallback = ip; // not VirtualBox — не VirtualBox
                }
            }
        } catch (Exception ignored) {
        }
        return fallback != null ? fallback : "127.0.0.1";
    }

    /** All our LAN / Tailscale IPv4 addresses, so Authority can give each joining player the one on his network — Усі наші LAN / Tailscale IPv4, щоб Authority дав кожному гравцю адресу в його мережі. */
    static String myIpsJson() {
        AuroraJson out = AuroraJson.arr();
        for (String ip : myIps()) out.add(ip);
        return out.toString();
    }

    /** Tailscale address (100.64.0.0/10) — Адреса Tailscale (100.64.0.0/10). */
    public static boolean isTailscaleIp(String ip) {
        String[] p = ip.split("\\.");
        if (p.length != 4 || !p[0].equals("100")) return false;
        int second = Strings.parseInt(p[1], -1);
        return second >= 64 && second <= 127;
    }

    /**
     * Our addresses friends can use: the set one (Aurora settings) first, then LAN ones, then Tailscale ones. The host dialog
     * shows them all: on a phone with Tailscale on, showing only one gave the Tailscale address to a friend at home.
     * — Наші адреси, якими можуть скористатися друзі: спершу задана (налаштування Aurora), потім LAN, потім Tailscale.
     * Вікно хоста показує їх усі: на телефоні з увімкненим Tailscale одна адреса давала другові вдома адресу Tailscale.
     */
    public static java.util.List<String> myIps() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        String s = Core.settings.getString("aurora-ip", "");
        if (!s.isEmpty() && !s.equals("127.0.0.1")) all.add(s);
        try {
            for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) all.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        java.util.List<String> lan = new java.util.ArrayList<>(), ts = new java.util.ArrayList<>();
        for (String ip : all) {
            if (ip.startsWith("192.168.56.")) continue; // VirtualBox host-only, nobody else can reach it — VirtualBox, до нього ніхто не достукається
            (isTailscaleIp(ip) ? ts : lan).add(ip);
        }
        lan.addAll(ts);
        return lan;
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

    /** Start our own Authority; true if it runs — Запустити свій Authority; true, якщо працює. */
    public static boolean startAuthority() {
        boolean ok = AuroraAuthority.start(authorityEnv);
        // The friend's Authority URL is not touched: authority() uses 127.0.0.1 on our own planet
        // — Адресу Authority друга не чіпаємо: authority() на своїй планеті сам бере 127.0.0.1
        if (ok) localAuthority = true;
        return ok;
    }

    public static void setHostToken(String t) {
        Core.settings.put("aurora-host-token", t == null ? "" : t.trim());
    }

    // ---------- Finding the planet from a sector — Пошук планети через сектор ----------

    /** Our sector's description + where our planet's Authority is — Опис нашого сектора + де Authority нашої планети. */
    public static String advertise(String desc) {
        String a = planetHost() ? AuroraWire.addr(myIp() + ":" + AuroraAuthority.port()) : authority();
        return (desc == null || desc.isEmpty() ? "" : desc + " ") + AUTH_MARK + a;
    }

    static JsonValue parse(Res r) {
        return new JsonReader().parse(new String(r.body, StandardCharsets.UTF_8));
    }

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

    /** sectorPath + who we are + our key for this sector — sectorPath + хто ми + наш ключ до цього сектора. */
    static String hostPath(Sector s, String action) {
        return sectorPath(s, action) + "?player=" + urlEnc(me()) + "&key=" + urlEnc(hostKeys.getOrDefault(sid(s), ""));
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

    // ---------- Heartbeat ----------

    /** Upload the save on the next beat (after a failure) — Залити сейв на наступному beat (після помилки). */
    static boolean forcePush;

    // ---------- Event saves — Збереження при подіях ----------

    static long lastPush;

    // ---------- Leaving — Вихід ----------

    /** The host of the sector we were in closed the server. Called from NetClient.kick — Хост сектора, де ми були клієнтом, закрив сервер. Викликається з NetClient.kick. */
    public static void hostLeft() {
        if (!asClient) return;
        endClient();
        ui.showInfoToast(tr("host.left"), 4f);
        returnFromSector();
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
        AuroraItems.reset();
        AuroraResearch.resetHistory();
        ownerHash = "";
        ownerIps = new ObjectSet<>();
        banUuids = new ObjectSet<>();
        banIps = new ObjectSet<>();
        AuroraResearch.reset();
        mindustry.graphics.AuroraMapRender.resetFade();
    }

    // ---------- Planet map — Карта планети ----------

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

    // ---------- Removing players: only the planet host — Видалення гравців: лише хост планети ----------

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

    /** Planet-wide ban from our own sector: we are the planet host (mayRemove checked it) — Блок на всю планету з нашого сектора: ми власник планети (перевірив mayRemove). */
    public static void planetBan(Player other) {
        if (other == null || other.con == null || !planetHost()) return;
        String uuid = AuroraUtil.planetUuid(other.con.uuid), ip = plainIp(other.con.address);
        banUuids.add(uuid);
        if (!ip.isEmpty()) banIps.add(ip);
        AuroraAuthority.ban(uuid, ip, Strings.stripColors(other.name), "planet host");
    }

    // ---------- Authority watch — Сторож Authority ----------

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

    // ---------- Hooks — Хуки ----------

    public static void init() {
        if (inited) return;
        inited = true;

        // One timer for the whole game — Один таймер на всю гру
        Timer.schedule(() -> {
            if (sessionActive()) {
                AuroraResearch.syncUnlocks();
                AuroraRules.syncRules();
                syncAdmin();
                AuroraItems.refreshItems();
                refreshMap();
            }
        }, 5f, 5f);
        // While the research tree is open, refresh the shared stock every 2 s — Поки відкрите дерево досліджень, оновлюємо спільний склад кожні 2 с
        Timer.schedule(() -> Core.app.post(() -> {
            // Plain refresh: "fresh" (waking every host) only on open and after a spend — Звичайне оновлення: "fresh" (будить усіх хостів) лише при відкритті й після витрати
            if (sessionActive() && ui != null && ui.research != null && ui.research.isShown()) AuroraItems.refreshItems(false);
        }), 2f, 2f);
        // Planet chat: new messages every 2 s while in a game — Чат планети: нові повідомлення кожні 2 с у грі
        Timer.schedule(() -> Core.app.post(AuroraChat::pollChat), 2f, 2f);
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
        AuroraResearch.init();
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
                    if (hosting && current == hs) AuroraItems.syncStock(hs);
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

    // ---------- Moved inside, kept here for callers — Перенесено всередину, лишено тут для викликів ----------

    /** Become the host, or join the existing host — Стати хостом або підключитись до наявного хоста. */
    public static void enter(Sector sector) {
        AuroraSectors.enter(sector);
    }

    static void enter(Sector sector, boolean retried) {
        AuroraSectors.enter(sector, retried);
    }

    public static void leave() {
        AuroraSectors.leave();
    }

    /**
     * Clean host exit: save -> upload -> release the sector -> close the server. then (if given) runs instead of returning to the planet.
     * — Чистий вихід хоста: зберегти -> залити -> звільнити сектор -> закрити сервер. then (якщо є) замість повернення на планету.
     */
    public static void leave(Runnable then) {
        AuroraSectors.leave(then);
    }

    public static void probePlanet(String ip, Cons<String> done) {
        AuroraJoin.probePlanet(ip, done);
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
        AuroraJoin.probeAndJoin(ip, port, done);
    }
}
