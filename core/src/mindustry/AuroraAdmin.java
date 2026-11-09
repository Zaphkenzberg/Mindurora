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
import static mindustry.AuroraNet.Res;
import static mindustry.AuroraNet.get;
import static mindustry.AuroraNet.hostingAurora;
import static mindustry.AuroraNet.parse;
import static mindustry.AuroraNet.planetHost;

/**
 * AuroraNet internals: who may remove players: the planet owner, planet bans synced from the Authority, checks of joining players.
 * — Нутрощі AuroraNet: хто може видаляти гравців: власник планети, бани планети з Authority, перевірка гравців, що заходять.
 */
final class AuroraAdmin {

    private AuroraAdmin() {
    }

    // ---------- Removing players: only the planet host — Видалення гравців: лише хост планети ----------

    /** SHA-256 of the planet host's uuid (Authority never gives the uuid itself) — SHA-256 uuid власника планети (сам uuid Authority не віддає). */
    static volatile String ownerHash = "";

    static volatile ObjectSet<String> ownerIps = new ObjectSet<>(), banUuids = new ObjectSet<>(), banIps = new ObjectSet<>();

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

    static boolean planetBanned(Player p) {
        if (p == null || p.con == null) return false;
        return banUuids.contains(p.con.uuid) || banIps.contains(plainIp(p.con.address));
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
                    if (hostingAurora()) Groups.player.each(AuroraAdmin::checkPlayer);
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
}
