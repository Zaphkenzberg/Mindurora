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
import static mindustry.AuroraAdmin.plainIp;
import static mindustry.AuroraLink.RemovedException;
import static mindustry.AuroraLink.hostKeys;
import static mindustry.AuroraLink.loadingSector;
import static mindustry.AuroraLink.probeable;
import static mindustry.AuroraLink.sectorPath;
import static mindustry.AuroraLink.startLoadingPings;
import static mindustry.AuroraLink.stopAuthority;
import static mindustry.AuroraNet.Res;
import static mindustry.AuroraNet.asClient;
import static mindustry.AuroraNet.authorityHost;
import static mindustry.AuroraNet.authorityOk;
import static mindustry.AuroraNet.checkAuthority;
import static mindustry.AuroraNet.clearLaunch;
import static mindustry.AuroraNet.clientEnd;
import static mindustry.AuroraNet.connectedOnce;
import static mindustry.AuroraNet.current;
import static mindustry.AuroraNet.enterTime;
import static mindustry.AuroraNet.forcePush;
import static mindustry.AuroraNet.get;
import static mindustry.AuroraNet.guestSession;
import static mindustry.AuroraNet.hostLive;
import static mindustry.AuroraNet.hostPath;
import static mindustry.AuroraNet.hosting;
import static mindustry.AuroraNet.lastGoneMsg;
import static mindustry.AuroraNet.lastPush;
import static mindustry.AuroraNet.launchCost;
import static mindustry.AuroraNet.launchFrom;
import static mindustry.AuroraNet.localAuthority;
import static mindustry.AuroraNet.me;
import static mindustry.AuroraNet.myIp;
import static mindustry.AuroraNet.myIpsJson;
import static mindustry.AuroraNet.myName;
import static mindustry.AuroraNet.myPort;
import static mindustry.AuroraNet.parse;
import static mindustry.AuroraNet.planetHost;
import static mindustry.AuroraNet.post;
import static mindustry.AuroraNet.putBytes;
import static mindustry.AuroraNet.request;
import static mindustry.AuroraNet.shutdownDone;
import static mindustry.AuroraNet.sid;
import static mindustry.AuroraNet.snapshot;
import static mindustry.AuroraNet.uploads;
import static mindustry.AuroraNet.wantHost;

/**
 * AuroraNet internals: a sector's life: claim, entering, hosting, heartbeat, saves on events, leaving.
 * — Нутрощі AuroraNet: життя сектора: клейм, вхід, хостинг, heartbeat, збереження при подіях, вихід.
 */
final class AuroraSectors {

    private AuroraSectors() {
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
        String body = AuroraJson.obj().put("ip", myIp()).putRaw("ips", myIpsJson()).put("port", myPort()).put("player", me()).put("name", myName())
            .put("key", hostKeys.getOrDefault(sid(s), "")).toString();
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

    static void push(Sector s, byte[][] pack) throws Exception {
        putBytes(hostPath(s, "save"), pack[0]);
        putBytes(hostPath(s, "info"), pack[1]);
    }

    /**
     * Heartbeat has its own thread: on the shared mainExecutor it could wait behind other network work, and a late beat makes
     * friends see the sector host as gone.
     * — Heartbeat має свій потік: у спільному mainExecutor він міг чекати за іншою мережевою роботою, а запізнілий beat
     * змушує друзів бачити хоста сектора зниклим.
     */
    static final java.util.concurrent.ExecutorService beatExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aurora-beat");
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

    /** Become the host, or join the existing host — Стати хостом або підключитись до наявного хоста. */
    public static void enter(Sector sector) {
        enter(sector, false);
    }

    static void enter(Sector sector, boolean retried) {
        AuroraResearch.syncUnlocks();
        ui.loadfrag.show("Aurora...");
        mainExecutor.submit(() -> {
            // Planet host's campaign rules before the sector loads — Правила кампанії хоста планети до завантаження сектора
            AuroraRules.syncRulesNow();
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
                        int code = AuroraItems.spendBlocking(cost);
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
                        AuroraItems.refreshItems(true);
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
                        // Only a real wake (the counter moved) syncs; a timed-out wait just waits again. The 5 s stockTask
                        // stays as the fallback — Синхронізує лише справжнє пробудження (лічильник змінився); /wait, що
                        // вийшов за часом, просто чекає знову. Запасним лишається stockTask кожні 5 с
                        long ver = parse(r).getLong("ver", since);
                        boolean woke = ver != since;
                        since = ver;
                        if (woke) Core.app.post(() -> {
                            if (hosting && current == s) AuroraItems.syncStock(s);
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

    static int beats, beatFails;

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
            if (hosting && s != null) AuroraItems.syncStock(s);
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
            beatExecutor.submit(() -> {
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
        AuroraJson a = AuroraJson.arr();
        Groups.player.each(p -> {
            if (!p.isLocal() && p.con == null) return;
            String uuid = p.isLocal() ? me() : AuroraUtil.planetUuid(p.con.uuid), ip = p.isLocal() ? "" : plainIp(p.con.address);
            a.add(AuroraJson.obj().put("uuid", uuid).put("ip", ip).put("name", Strings.stripColors(p.name)).put("pid", p.id));
        });
        return a.toString().getBytes(StandardCharsets.UTF_8);
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
            if (!p.isLocal() && p.con != null && out.contains(AuroraUtil.planetUuid(p.con.uuid))) {
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
    static void shutdownWork(boolean mainThread) {
        // Same lock as before the split (AuroraNet.class) — Той самий замок, що й до поділу (AuroraNet.class)
        synchronized (AuroraNet.class) {
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
            if (localAuthority) stopAuthority(); // snapshot/release first, then stop — спершу знімок/release, потім stop
            AuroraWire.closeAll();
        }
    }

    /** Reset "client of a sector" state — Скинути стан "клієнт сектора". */
    static void endClient() {
        asClient = false;
        connectedOnce = false;
        current = null;
        clientEnd = Time.millis();
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
}
