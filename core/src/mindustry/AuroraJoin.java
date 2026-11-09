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
import static mindustry.AuroraLink.advertisedAuthority;
import static mindustry.AuroraLink.anonymous;
import static mindustry.AuroraLink.probeable;
import static mindustry.AuroraLink.protoMismatch;
import static mindustry.AuroraLink.quietProto;
import static mindustry.AuroraLink.remoteProtocol;
import static mindustry.AuroraNet.PROTOCOL;
import static mindustry.AuroraNet.Res;
import static mindustry.AuroraNet.authority;
import static mindustry.AuroraNet.authorityOk;
import static mindustry.AuroraNet.guestSession;
import static mindustry.AuroraNet.parse;
import static mindustry.AuroraNet.request;
import static mindustry.AuroraNet.wipeGuestData;

/**
 * AuroraNet internals: Join Game: is this address an Aurora planet, and joining it.
 * — Нутрощі AuroraNet: Приєднатися до гри: чи ця адреса — планета Aurora, і вхід на неї.
 */
final class AuroraJoin {

    private AuroraJoin() {
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
}
