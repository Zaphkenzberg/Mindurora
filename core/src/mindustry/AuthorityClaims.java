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

import static mindustry.AuroraAuthority.dir;
import static mindustry.AuroraAuthority.port;
import static mindustry.AuthorityAccess.isTailscale;
import static mindustry.AuthorityRoutes.Exchange;
import static mindustry.AuthorityRoutes.json;
import static mindustry.AuthorityRoutes.send;

/**
 * Authority internals: who hosts which sector: host states after heartbeats, the claim decision, sector saves and infos.
 * — Нутрощі Authority: хто хостить який сектор: стани хоста після heartbeat, рішення при клеймі, сейви й info секторів.
 */
final class AuthorityClaims {

    private AuthorityClaims() {
    }

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
        /** Display name and the real address the claim came from (for bans) — Ім'я і справжня адреса, з якої прийшов claim (для банів). */
        String name = "", addr = "";
        /** Secret given to this host on claim; every host call must carry it — Секрет, виданий цьому хосту при клеймі; кожен виклик хоста має його нести. */
        String key = "";
        /** All LAN / Tailscale addresses the host has; a joining player gets the one on his own network — Усі LAN / Tailscale адреси хоста; гравець, що заходить, отримує ту, що в його мережі. */
        String[] ips = {};

        Host(String ip, int port, String player, long lastSeen, boolean ready) {
            this.ip = ip;
            this.port = port;
            this.player = player;
            this.lastSeen = lastSeen;
            this.ready = ready;
        }

        Host with(String name, String addr) {
            this.name = name == null ? "" : name;
            this.addr = addr == null ? "" : addr;
            return this;
        }

        Host keyed(String key) {
            this.key = key;
            return this;
        }

        Host ips(String[] ips) {
            this.ips = ips == null ? new String[0] : ips;
            return this;
        }

        /**
         * The address for a joining player: one on the same /24 as him (a phone on Wi-Fi gets the PC's Wi-Fi address,
         * not a dead 169.254 adapter or a Tailscale one), else a Tailscale one if he comes over Tailscale, else ip.
         * — Адреса для гравця, що заходить: у тій самій /24, що й він (телефон у Wi-Fi отримає Wi-Fi адресу ПК, а не мертвий
         * адаптер 169.254 чи Tailscale), інакше Tailscale, якщо він прийшов через Tailscale, інакше ip.
         */
        String ipFor(InetAddress who) {
            if (who == null || ips.length == 0) return ip;
            byte[] r = who.getAddress();
            if (r.length != 4) return ip;
            boolean viaTailscale = isTailscale(who);
            String tailscale = null;
            for (String c : ips) {
                try {
                    byte[] b = InetAddress.getByName(c).getAddress();
                    if (b.length != 4) continue;
                    if (b[0] == r[0] && b[1] == r[1] && b[2] == r[2]) return c;
                    if (tailscale == null && isTailscale(InetAddress.getByAddress(b))) tailscale = c;
                } catch (Exception ignored) {
                }
            }
            return viaTailscale && tailscale != null ? tailscale : ip;
        }

        /** Still loading the sector: alive, but not listening yet — Ще вантажить сектор: живий, але ще не слухає. */
        Host touch(long now) {
            return new Host(ip, port, player, now, ready).with(name, addr).keyed(key).ips(ips);
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
            return new Host(ip, port, player, now, true).with(name, addr).keyed(key).ips(ips);
        }

        /** Player UUID is never sent: it is the player's "key" to the sector — UUID гравця не віддаємо: це його "ключ" до сектора. */
        String json(long now) {
            return AuroraJson.obj().put("ip", ip).put("port", port).put("lastSeen", lastSeen).put("ready", ready)
                .put("state", STATE_NAMES[stateAt(now)]).toString();
        }
    }

    /**
     * Who holds a sector after a claim. A free (or dead) sector goes to the claimer; a live one stays with its host,
     * unless the claim is that same host coming back with its key (then it keeps the key).
     * — Хто тримає сектор після клейму. Вільний (або мертвий) сектор дістається тому, хто клеймить; живий лишається
     * за своїм хостом, хіба що це той самий хост повертається зі своїм ключем (тоді ключ зберігається).
     */
    static Host pickHost(Host old, Host mine, String who, String theirKey, long now) {
        if (old == null || !old.alive(now)) return mine;
        if (old.player.equals(who) && !old.key.isEmpty() && old.key.equals(theirKey)) return mine.keyed(old.key);
        return old;
    }

    static final ConcurrentHashMap<String, Host> hosts = new ConcurrentHashMap<>();

    /**
     * Sectors that have a save on disk. Saves are NOT kept in memory: read from disk when someone downloads one,
     * so junk uploads can't fill the game's memory (and crash it again on every start).
     * — Сектори, що мають сейв на диску. Сейви НЕ тримаються в пам'яті: читаються з диска, коли хтось його завантажує,
     * тож сміттєві заливання не заб'ють пам'ять гри (і не валитимуть її знову при кожному запуску).
     */
    static final Set<String> saves = ConcurrentHashMap.newKeySet();

    /** All sector saves together may not take more disk than this — Усі сейви секторів разом не можуть займати на диску більше. */
    static final long SAVES_QUOTA = 1024L * 1024 * 1024;

    static final ConcurrentHashMap<String, String> infos = new ConcurrentHashMap<>();

    /** Sector state bits from its info, cached (1 core, 2 waves, 4 attack) — Біти стану сектора з info, кешовані (1 ядро, 2 хвилі, 4 атака). */
    static final ConcurrentHashMap<String, Integer> infoBits = new ConcurrentHashMap<>();

    // ---------- Disk — Диск ----------

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

    // ---------- Planet bans — Блокування на планеті ----------

    /** Frees his sectors right away (no waiting for timeouts) — Звільняє його сектори одразу (без очікування таймаутів). */
    static void dropHost(String uuid, String ip) {
        hosts.forEach((id, h) -> {
            if (h.player.equals(uuid) || (ip != null && !ip.isEmpty() && ip.equals(h.addr))) {
                if (hosts.remove(id, h)) {
                    sectorPlayers.remove(id);
                    Log.info("Aurora: sector @ freed (host removed by the planet host)", id);
                }
            }
        });
    }

    /** Players of each live sector, reported by its host: sector -> [{uuid, ip, name}] — Гравці кожного живого сектора від його хоста. */
    static final ConcurrentHashMap<String, List<String[]>> sectorPlayers = new ConcurrentHashMap<>();

    static String cut(String s) {
        return s == null ? "" : s.length() > 40 ? s.substring(0, 40) : s;
    }

    /** 403 if the player is not this sector's host. True = denied — 403, якщо гравець не хост цього сектора. true = відмовлено. */
    static boolean denyNonHost(Exchange ex, String id, String player, String key) throws IOException {
        if (isHost(id, player, key)) return false;
        send(ex, 403, "");
        return true;
    }

    static boolean isHost(String id, String player, String key) {
        Host h = hosts.get(id);
        return h != null && h.player.equals(player) && !h.key.isEmpty() && h.key.equals(key);
    }

    // ---------- Utils — Утиліти ----------

    /** Starts like a Mindustry save: zlib stream whose first bytes are "MSAV" — Починається як сейв Mindustry: zlib-потік, перші байти якого "MSAV". */
    static boolean isMsav(byte[] b) {
        try (java.util.zip.InflaterInputStream in = new java.util.zip.InflaterInputStream(new ByteArrayInputStream(b))) {
            byte[] head = new byte[4];
            int n = 0;
            while (n < 4) {
                int r = in.read(head, n, 4 - n);
                if (r < 0) return false;
                n += r;
            }
            return head[0] == 'M' && head[1] == 'S' && head[2] == 'A' && head[3] == 'V';
        } catch (Throwable e) {
            return false;
        }
    }

    /** Bytes of all saves on disk except this sector's (it is being replaced) — Байти всіх сейвів на диску, крім цього сектора (його замінюють). */
    static long savesSize(String except) {
        long total = 0;
        for (Fi f : dir().list()) {
            if (f.name().endsWith(".msav") && !f.name().equals(except + ".msav")) total += f.length();
        }
        return total;
    }

    /** A real sector of a real planet: "<planet>-<index>" — Справжній сектор справжньої планети: "<планета>-<номер>". */
    static boolean validSector(String id) {
        if (!safe(id)) return false;
        int d = id.lastIndexOf('-');
        if (d <= 0) return false;
        mindustry.type.Planet pl = Vars.content.planet(id.substring(0, d));
        int n = Strings.parseInt(id.substring(d + 1), -1);
        return pl != null && n >= 0 && n < pl.sectors.size;
    }

    /** The id goes into a file name, so safe characters only — id іде в назву файла, тож лише безпечні символи. */
    static boolean safe(String id) {
        return id.matches("[A-Za-z0-9_\\-]{1,64}");
    }

    static String shortId(String player) {
        return player == null ? "?" : player.substring(0, Math.min(6, player.length()));
    }
}
