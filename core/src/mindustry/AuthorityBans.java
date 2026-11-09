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
import static mindustry.AuroraAuthority.writeAtomic;

/**
 * Authority internals: planet bans and short kicks: who may not come back, and for how long.
 * — Нутрощі Authority: бани й короткі кіки на планеті: хто не може повернутись і як довго.
 */
final class AuthorityBans {

    private AuthorityBans() {
    }

    // ---------- Planet bans — Блокування на планеті ----------

    /** uuid -> {ip, name} */
    static final Map<String, String[]> bans = new ConcurrentHashMap<>();

    static final Set<String> bannedIps = ConcurrentHashMap.newKeySet();

    /** Planet host's player uuid (never banned) — uuid гравця-хоста планети (його не можна заблокувати). */
    static volatile String ownerUuid = "";

    static void loadBans() {
        Fi f = dir().child("bans.txt");
        if (!f.exists()) return;
        for (String line : f.readString().split("\n")) {
            String[] c = line.split("\t", -1);
            if (c.length < 3 || c[0].isEmpty()) continue;
            bans.put(c[0], new String[]{c[1], c[2]});
            if (!c[1].isEmpty()) bannedIps.add(c[1]);
        }
    }

    static void saveBans() {
        // Same lock as before the split (AuroraAuthority.class): these writes must not interleave with each other
        // — Той самий замок, що й до поділу (AuroraAuthority.class): ці записи не мають перемішуватись між собою
        synchronized (AuroraAuthority.class) {
            try {
                StringBuilder sb = new StringBuilder();
                bans.forEach((u, v) -> sb.append(u).append('\t').append(v[0]).append('\t').append(v[1].replace('\t', ' ').replace('\n', ' ')).append('\n'));
                writeAtomic(dir().child("bans.txt"), sb.toString());
            } catch (Throwable e) {
                Log.err("Aurora bans save failed", e);
            }
        }
    }

    /** Kicked by the planet host: uuid -> may come back at (ms) — Видалені хостом планети: uuid -> коли можна повернутись (мс). */
    static final ConcurrentHashMap<String, Long> kickedUntil = new ConcurrentHashMap<>();

    static final long KICK_MS = 60_000L;

    /** Banned or kicked less than a minute ago — Заблокований або видалений менше хвилини тому. */
    static boolean removed(String uuid) {
        if (uuid == null || uuid.isEmpty() || uuid.equals(ownerUuid)) return false;
        if (bans.containsKey(uuid)) return true;
        Long t = kickedUntil.get(uuid);
        return t != null && t > System.currentTimeMillis();
    }

    /** {"banned":true} or {"kicked":seconds left} — {"banned":true} або {"kicked":скільки секунд лишилось}. */
    static String removedJson(String uuid) {
        if (bans.containsKey(uuid)) return AuroraJson.obj().put("banned", true).toString();
        Long t = kickedUntil.get(uuid);
        long left = t == null ? 0 : Math.max(1, (t - System.currentTimeMillis() + 999) / 1000);
        return AuroraJson.obj().put("kicked", left).toString();
    }
}
