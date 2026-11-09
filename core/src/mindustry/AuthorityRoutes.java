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
import static mindustry.AuroraAuthority.env;
import static mindustry.AuroraAuthority.kick;
import static mindustry.AuroraAuthority.newKey;
import static mindustry.AuroraAuthority.sha256;
import static mindustry.AuroraAuthority.writeAtomic;
import static mindustry.AuthorityAccess.allowed;
import static mindustry.AuthorityAccess.fromThisPc;
import static mindustry.AuthorityAccess.isLoopback;
import static mindustry.AuthorityAccess.ownIps;
import static mindustry.AuthorityAccess.plainAddr;
import static mindustry.AuthorityAccess.privateLiteral;
import static mindustry.AuthorityBans.bannedIps;
import static mindustry.AuthorityBans.bans;
import static mindustry.AuthorityBans.ownerUuid;
import static mindustry.AuthorityBans.removed;
import static mindustry.AuthorityBans.removedJson;
import static mindustry.AuthorityChat.chatJson;
import static mindustry.AuthorityChat.chatPost;
import static mindustry.AuthorityClaims.Host;
import static mindustry.AuthorityClaims.OK;
import static mindustry.AuthorityClaims.SAVES_QUOTA;
import static mindustry.AuthorityClaims.STATE_NAMES;
import static mindustry.AuthorityClaims.cut;
import static mindustry.AuthorityClaims.denyNonHost;
import static mindustry.AuthorityClaims.hosts;
import static mindustry.AuthorityClaims.infoBits;
import static mindustry.AuthorityClaims.infos;
import static mindustry.AuthorityClaims.isHost;
import static mindustry.AuthorityClaims.isMsav;
import static mindustry.AuthorityClaims.pickHost;
import static mindustry.AuthorityClaims.putInfo;
import static mindustry.AuthorityClaims.saves;
import static mindustry.AuthorityClaims.savesSize;
import static mindustry.AuthorityClaims.sectorPlayers;
import static mindustry.AuthorityClaims.shortId;
import static mindustry.AuthorityClaims.validSector;
import static mindustry.AuthorityEconomy.NONE;
import static mindustry.AuthorityEconomy.Op;
import static mindustry.AuthorityEconomy.WAIT_MAX;
import static mindustry.AuthorityEconomy.Waiter;
import static mindustry.AuthorityEconomy.countsJson;
import static mindustry.AuthorityEconomy.freshStock;
import static mindustry.AuthorityEconomy.opsJson;
import static mindustry.AuthorityEconomy.pending;
import static mindustry.AuthorityEconomy.readCounts;
import static mindustry.AuthorityEconomy.sanitizeStock;
import static mindustry.AuthorityEconomy.saveEconomy;
import static mindustry.AuthorityEconomy.shown;
import static mindustry.AuthorityEconomy.spend;
import static mindustry.AuthorityEconomy.stock;
import static mindustry.AuthorityEconomy.stockLock;
import static mindustry.AuthorityEconomy.stockTime;
import static mindustry.AuthorityEconomy.waiters;
import static mindustry.AuthorityEconomy.wakeVer;
import static mindustry.AuthorityResearch.isContent;
import static mindustry.AuthorityResearch.researchCost;
import static mindustry.AuthorityResearch.saveUnlocks;
import static mindustry.AuthorityResearch.unlocked;
import static mindustry.AuthorityResearch.unlockedBy;
import static mindustry.AuthorityRules.rulesJson;

/**
 * Authority internals: requests in, answers out: the routing of every request and the request/answer object (Exchange).
 * — Нутрощі Authority: запити на вхід, відповіді на вихід: маршрутизація кожного запиту й об'єкт запиту/відповіді (Exchange).
 */
final class AuthorityRoutes {

    private AuthorityRoutes() {
    }

    // ---------- Requests — Запити ----------

    static void handle(Exchange ex) {
        try {
            // Who may knock: this PC, LAN, Tailscale — Хто може стукати: цей ПК, LAN, Tailscale
            if (!allowed(ex.getRemoteAddress().getAddress())) {
                send(ex, 403, "");
                return;
            }

            // Banned on this planet: a clear answer (not a dropped connection), so his game stops at once
            // — Заблокований на цій планеті: чітка відповідь (а не обірване з'єднання), щоб його гра зупинилась одразу
            if (!fromThisPc(ex) && bannedIps.contains(plainAddr(ex.getRemoteAddress().getAddress()))) {
                json(ex, 403, AuroraJson.obj().put("banned", true).toString());
                return;
            }

            String token = env.hostToken(); // our planet's password, not the one for friends' planets — пароль нашої планети, а не той, що для планет друзів
            if (!token.isEmpty() && !java.security.MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), ex.password.getBytes(StandardCharsets.UTF_8))) {
                send(ex, 401, "");
                return;
            }

            URI uri = ex.getRequestURI();
            String m = ex.req.method;
            String[] p = uri.getPath().split("/");
            boolean get = m.equals("GET");

            // /version is open; everything else needs the same protocol — /version відкритий; решта — лише з тією самою версією протоколу
            if (get && p.length == 2 && p[1].equals("version")) {
                json(ex, 200, AuroraJson.obj().put("proto", AuroraWire.PROTOCOL).toString());
                return;
            }
            if (ex.req.proto != AuroraWire.PROTOCOL) {
                json(ex, 426, AuroraJson.obj().put("proto", AuroraWire.PROTOCOL).toString());
                return;
            }

            String path = p.length >= 2 ? p[1] : "";

            if (ex.req.body.length > bodyLimit(uri.getPath())) {
                send(ex, 413, ""); // too large for this request — завелике для цього запиту
                return;
            }

            if (get && p.length == 2 && path.equals("unlocks")) {
                // {"name": "who researched", ...}, "" if unknown — {"назва": "хто дослідив", ...}, "" якщо невідомо
                AuroraJson o = AuroraJson.obj();
                for (String s : unlocked) o.put(s, unlockedBy.getOrDefault(s, ""));
                json(ex, 200, o.toString());

            } else if (get && p.length == 3 && path.equals("unlock")) {
                if (!isContent(p[2])) {
                    send(ex, 400, "");
                    return;
                }
                String by = query(uri, "by");
                if (by.length() > 40) by = by.substring(0, 40);
                // Research with a cost opens only through a paid /spend (or from this PC) — Дослідження з ціною відкривається лише через оплачений /spend (або з цього ПК)
                if (!unlocked.contains(p[2]) && researchCost(p[2]) != null && !fromThisPc(ex)) {
                    send(ex, 403, "");
                    return;
                }
                if (unlocked.add(p[2])) {
                    if (!by.isEmpty()) unlockedBy.put(p[2], by);
                    saveUnlocks();
                }
                json(ex, 200, AuroraJson.obj().put("name", p[2]).put("unlocked", true).toString());

            } else if (get && p.length == 2 && path.equals("sectors")) {
                long now = System.currentTimeMillis();
                AuroraJson o = AuroraJson.obj();
                hosts.forEach((id, h) -> {
                    if (h.alive(now)) o.putRaw(id, h.json(now));
                });
                json(ex, 200, o.toString());

            } else if (get && p.length == 2 && path.equals("owner")) {
                // Who the planet host is: a hash of the uuid (never the uuid itself) + own IPs, so sector hosts can give him admin
                // — Хто хост планети: хеш uuid (ніколи сам uuid) + власні IP, щоб хости секторів давали йому адмінку
                AuroraJson ips = AuroraJson.arr();
                for (String ip : ownIps()) ips.add(ip);
                json(ex, 200, AuroraJson.obj().put("uuidHash", sha256(ownerUuid)).put("ips", ips).toString());

            } else if (get && p.length == 2 && path.equals("bans")) {
                AuroraJson uuids = AuroraJson.arr(), ips = AuroraJson.arr();
                for (String u : bans.keySet()) uuids.add(u);
                for (String ip : bannedIps) ips.add(ip);
                json(ex, 200, AuroraJson.obj().put("uuids", uuids).put("ips", ips).toString());

            } else if (get && p.length == 2 && path.equals("rules")) {
                // Planet campaign rules set by the planet host: {"serpulo":{...}, ...} — Правила кампанії планети, які задає хост планети
                json(ex, 200, rulesJson());

            } else if (get && p.length == 2 && path.equals("mode")) {
                // Is the planet host in Aurora right now (not normal campaign / own game) — Чи хост планети зараз в Aurora (а не у звичайній кампанії / своїй грі)
                json(ex, 200, AuroraJson.obj().put("active", env.planetActive()).toString());

            } else if (get && p.length == 3 && path.equals("sectors") && p[2].equals("state")) {
                // {"id": bits}: 1 core, 2 waves still running, 4 attacking an enemy base — 1 ядро, 2 хвилі ще йдуть, 4 атака на ворожу базу
                AuroraJson o = AuroraJson.obj();
                for (String s : saves) o.put(s, infoBits.getOrDefault(s, 1 | 2));
                json(ex, 200, o.toString());

            } else if (p.length == 4 && path.equals("sector") && validSector(p[2])) {
                sector(ex, m, p[2], p[3], query(uri, "player"), query(uri, "key"));

            } else if (get && p.length == 2 && path.equals("items")) {
                // ?fresh=1: ask the hosts for their current stock first — ?fresh=1: спершу попросити в хостів поточний склад
                if (query(uri, "fresh").equals("1")) freshStock();
                // Shared stock = sum of all sectors minus pending ops — Спільний склад = сума всіх секторів мінус ще не застосовані списання
                Map<String, Integer> sum = new TreeMap<>();
                stock.forEach((sec, items) -> {
                    for (String item : items.keySet()) sum.merge(item, shown(sec, item), Integer::sum);
                });
                sum.values().removeIf(v -> v <= 0);
                json(ex, 200, countsJson(sum));

            } else if (m.equals("POST") && p.length == 2 && path.equals("spend")) {
                spend(ex);

            } else if (p.length == 2 && path.equals("chat")) {
                // Planet chat: everyone on the planet, from any sector — Чат планети: усі на планеті, з будь-якого сектора
                if (get) json(ex, 200, chatJson(Strings.parseLong(query(uri, "since"), -1)));
                else if (m.equals("POST")) chatPost(ex, query(uri, "player"));
                else send(ex, 404, "");

            } else {
                send(ex, 404, "");
            }
        } catch (Throwable e) {
            Log.err("Aurora Authority request failed", e);
            try {
                send(ex, 400, "");
            } catch (Throwable ignored) {
            }
        } finally {
            ex.close();
        }
    }

    // ---------- Planet bans — Блокування на планеті ----------

    /** /sector/{id}/{action} */
    static void sector(Exchange ex, String m, String id, String action, String player, String key) throws Exception {
        boolean get = m.equals("GET"), post = m.equals("POST");

        if (post && action.equals("claim")) {
            JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            String who = j.getString("player");
            // The planet host's uuid only from the planet host's own PC: someone who learned it can't hide behind it
            // — uuid власника планети лише з його власного ПК: той, хто його дізнався, не сховається за ним
            if (who.equals(ownerUuid) && !fromThisPc(ex)) {
                json(ex, 403, "{}");
                return;
            }
            if (removed(who)) {
                // Banned, or kicked less than a minute ago: say which — Заблокований або видалений менше хвилини тому: кажемо, що саме
                json(ex, 403, removedJson(who));
                return;
            }
            String nm = j.getString("name", "");
            if (nm.length() > 40) nm = nm.substring(0, 40);
            String theirKey = j.getString("key", "");
            String fromAddr = ex.getRemoteAddress().getAddress().getHostAddress();
            // A game hosts one sector at a time: claiming another frees the previous one, and one address may hold
            // at most 2 live sectors. So nobody can grab many sectors at once to fill them with junk.
            // — Гра хостить один сектор за раз: клейм іншого звільняє попередній, а одна адреса може тримати
            // щонайбільше 2 живі сектори. Тож ніхто не захопить багато секторів, щоб заповнити їх сміттям.
            hosts.forEach((oid, h) -> {
                // same uuid AND same address: a copied uuid from elsewhere can't free someone's sector
                // — той самий uuid І та сама адреса: скопійований uuid з іншого місця не звільнить чужий сектор
                if (!oid.equals(id) && h.player.equals(who) && h.addr.equals(fromAddr) && hosts.remove(oid, h)) sectorPlayers.remove(oid);
            });
            if (!fromThisPc(ex)) {
                int sameAddr = 0;
                for (Map.Entry<String, Host> e : hosts.entrySet()) {
                    Host h = e.getValue();
                    if (!e.getKey().equals(id) && h.alive(now) && h.addr.equals(fromAddr)) sameAddr++;
                }
                if (sameAddr >= 2) {
                    json(ex, 429, "{}");
                    return;
                }
            }
            // The address friends will connect to: the one the host reports (e.g. its Tailscale IP), but only a LAN / Tailscale
            // literal; anything else (a public server, a name) becomes the address the claim actually came from
            // — Адреса, до якої підключатимуться друзі: та, яку повідомив хост (напр. його Tailscale-IP), але лише літерал
            // LAN / Tailscale; будь-що інше (публічний сервер, ім'я) замінюється адресою, з якої реально прийшов клейм
            String reported = j.getString("ip", "");
            String joinIp = privateLiteral(reported) ? reported : fromAddr;
            int joinPort = j.getInt("port", Vars.port);
            if (joinPort <= 0 || joinPort > 65535) joinPort = Vars.port;
            // All its addresses (newer games send them), LAN / Tailscale literals only — Усі його адреси (нові версії гри шлють їх), лише літерали LAN / Tailscale
            List<String> ipList = new ArrayList<>();
            JsonValue ipsJson = j.get("ips");
            for (JsonValue c = ipsJson == null ? null : ipsJson.child; c != null && ipList.size() < 8; c = c.next) {
                if (c.isString() && privateLiteral(c.asString()) && !c.asString().startsWith("127.")) ipList.add(c.asString());
            }
            Host mine = new Host(joinIp, joinPort, who, now, false).with(nm, fromAddr).keyed(newKey()).ips(ipList.toArray(new String[0]));
            Host prev = hosts.get(id);
            // A live host stays unless the claim comes with its key (the same game re-claiming); a dead one is replaced.
            // Same uuid without the key (a copied uuid, or our own game restarted) waits until the old entry expires.
            // — Живий хост лишається, якщо клейм не несе його ключ (та сама гра клеймить знову); мертвий замінюємо.
            // Той самий uuid без ключа (скопійований uuid або наша ж перезапущена гра) чекає, поки старий запис застаріє.
            Host cur = hosts.compute(id, (k, old) -> pickHost(old, mine, who, theirKey, now));
            boolean sameButLocked = cur != mine && cur.player.equals(who);
            Log.info("Authority claim @ by @ from @ -> @ | prev=@", id, shortId(mine.player), mine.ip, cur == mine ? "HOST" : "client",
                prev == null ? "none" : shortId(prev.player) + " age=" + (now - prev.lastSeen) + "ms ready=" + prev.ready);

            if (cur == mine) json(ex, 200, AuroraJson.obj().put("role", "host").put("key", mine.key).toString());
            else if (sameButLocked) json(ex, 200, AuroraJson.obj().put("role", "wait").put("state", "lost").put("retry", cur.secondsToFree(now)).toString());
            else if (cur.stateAt(now) == OK) json(ex, 200, AuroraJson.obj().put("role", "client").put("ip", cur.ipFor(ex.getRemoteAddress().getAddress())).put("port", cur.port).toString());
            // Host is silent: neither join it nor take the sector, wait — Хост мовчить: ні підключатись, ні забирати сектор — чекати
            else json(ex, 200, AuroraJson.obj().put("role", "wait").put("state", STATE_NAMES[cur.stateAt(now)]).put("retry", cur.secondsToFree(now)).toString());

        } else if (post && action.equals("heartbeat")) {
            // Body (new clients): the sector's players [{uuid, ip, name, pid}], the host himself included — Тіло (нові клієнти): гравці сектора [{uuid, ip, name}]
            byte[] hb = readBody(ex);
            if (removed(player)) {
                hosts.computeIfPresent(id, (k, h) -> h.player.equals(player) ? null : h);
                sectorPlayers.remove(id);
                send(ex, 403, ""); // the host himself was removed — видалили самого хоста
                return;
            }
            if (!isHost(id, player, key)) {
                send(ex, 404, ""); // not the host (or wrong key): claim again — не хост (або не той ключ): клейм знову
                return;
            }
            // ?loading=1: still downloading / loading the sector — keep the claim alive, not ready yet
            // — ?loading=1: ще завантажує сектор — тримаємо клейм живим, але ще не готовий
            if (query(ex.getRequestURI(), "loading").equals("1")) {
                long t = System.currentTimeMillis();
                hosts.computeIfPresent(id, (k, h) -> h.player.equals(player) && h.key.equals(key) ? h.touch(t) : h);
                send(ex, 200, "");
                return;
            }
            if (hb.length > 0) {
                try {
                    List<String[]> list = new ArrayList<>();
                    for (JsonValue c = new JsonReader().parse(new String(hb, StandardCharsets.UTF_8)).child; c != null && list.size() < 64; c = c.next) {
                        list.add(new String[]{AuroraUtil.planetUuid(c.getString("uuid", "")), c.getString("ip", ""), cut(c.getString("name", "")), String.valueOf(c.getInt("pid", -1))});
                    }
                    sectorPlayers.put(id, list);
                } catch (Throwable ignored) {
                }
            }
            boolean[] ok = {false};
            long now = System.currentTimeMillis();
            hosts.computeIfPresent(id, (k, h) -> {
                if (!h.player.equals(player) || !h.key.equals(key)) return h;
                ok[0] = true;
                return h.beat(now);
            });
            // 404 = you are not the host anymore (Authority restarted or sector taken), claim again
            // — 404 = ти тут більше не хост (Authority перезапустився або сектор забрали), заклейм знову
            if (!ok[0]) {
                send(ex, 404, "");
                return;
            }
            // Players of this sector to throw out — Гравці цього сектора, яких треба викинути
            AuroraJson kick = AuroraJson.arr();
            for (String[] pl : sectorPlayers.getOrDefault(id, Collections.emptyList())) {
                if (removed(pl[0]) || bannedIps.contains(pl[1])) kick.add(pl[0]);
            }
            json(ex, 200, AuroraJson.obj().put("kick", kick).toString());

        } else if (post && action.equals("release")) {
            boolean[] own = {false};
            hosts.computeIfPresent(id, (k, h) -> {
                if (!h.player.equals(player) || !h.key.equals(key)) return h;
                own[0] = true;
                return null;
            });
            if (own[0]) sectorPlayers.remove(id);
            Log.info("Authority release @ by @ -> @", id, shortId(player), own[0] ? "REMOVED" : "ignored");
            send(ex, 200, "");

        } else if (post && action.equals("save")) {
            byte[] b = readBody(ex); // body first, then rights (client gets a clean answer) — спершу тіло, потім права (клієнт отримає чисту відповідь)
            if (denyNonHost(ex, id, player, key)) return;
            // A real Mindustry save (zlib stream starting with MSAV), and within the disk quota
            // — Справжній сейв Mindustry (zlib-потік, що починається з MSAV), і в межах квоти диска
            if (!isMsav(b)) {
                send(ex, 400, "");
                return;
            }
            if (savesSize(id) + b.length > SAVES_QUOTA) {
                Log.warn("Aurora Authority: save of @ refused, saves quota (@ MB) is full", id, SAVES_QUOTA / 1024 / 1024);
                send(ex, 507, "");
                return;
            }
            writeAtomic(dir().child(id + ".msav"), b);
            saves.add(id);
            // ?ops=a,b: spends already inside this save, acked together with it (no window where they could come back or be taken twice)
            // — ?ops=a,b: списання, які вже є в цьому сейві, підтверджуються разом з ним (нема вікна, де вони повернуться або знімуться двічі)
            String ops = query(ex.getRequestURI(), "ops");
            if (!ops.isEmpty()) {
                Set<String> ids = new HashSet<>(Arrays.asList(ops.split(",")));
                List<Op> list = pending.get(id);
                if (list != null && list.removeIf(o -> ids.contains(o.id))) saveEconomy();
            }
            send(ex, 200, "");

        } else if (get && action.equals("save")) {
            Fi f = dir().child(id + ".msav");
            if (!saves.contains(id) || !f.exists()) send(ex, 404, "");
            else sendBytes(ex, 200, f.readBytes());

        } else if (post && action.equals("info")) {
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (denyNonHost(ex, id, player, key)) return;
            // Must be a JSON object of sane depth: every player who enters this sector will load it
            // — Має бути JSON-об'єкт нормальної глибини: його завантажить кожен, хто зайде в цей сектор
            try {
                JsonValue v = new JsonReader().parse(s);
                if (v == null || !v.isObject() || mindustry.io.JsonIO.depth(v) > mindustry.io.JsonIO.maxRemoteDepth) throw new IllegalArgumentException();
            } catch (Throwable bad) {
                send(ex, 400, "");
                return;
            }
            putInfo(id, s);
            writeAtomic(dir().child(id + ".info"), s);
            send(ex, 200, "");

        } else if (get && action.equals("info")) {
            String s = infos.get(id);
            if (s == null) send(ex, 404, "");
            else json(ex, 200, s);

        } else if (post && action.equals("items")) {
            // Host pushes its core stock (every heartbeat) — Хост відправляє склад свого ядра (кожен heartbeat)
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if (denyNonHost(ex, id, player, key)) return;
            stock.put(id, sanitizeStock(id, readCounts(new JsonReader().parse(s))));
            stockTime.put(id, System.currentTimeMillis());
            synchronized (stockLock) {
                stockLock.notifyAll();
            }
            saveEconomy();
            send(ex, 200, "");

        } else if (get && action.equals("wait")) {
            // Long poll: the host waits here; returns when something needs its stock — Long poll: хост чекає тут; повертається, коли комусь потрібен його склад
            if (denyNonHost(ex, id, player, key)) return;
            long since = Strings.parseLong(query(ex.getRequestURI(), "since"), -1);
            long[] v = wakeVer.computeIfAbsent(id, k -> new long[1]);
            synchronized (v) {
                // Something new already, or Authority restarted (counter below the host's): answer now
                // — Уже є нове, або Authority перезапустився (лічильник менший за хостовий): відповідаємо зараз
                if (v[0] != since) {
                    json(ex, 200, AuroraJson.obj().put("ver", v[0]).toString());
                    return;
                }
                // Park the request without holding a thread: wake() or the sweeper answers it — Паркуємо запит без зайнятого потоку: відповість wake() або прибиральник
                ex.deferred = true;
                waiters.computeIfAbsent(id, k -> new CopyOnWriteArrayList<>()).add(new Waiter(ex, System.currentTimeMillis() + WAIT_MAX));
            }

        } else if (get && action.equals("pending")) {
            // Host takes spend ops queued for its sector — Хост забирає списання, що чекають на його сектор
            if (denyNonHost(ex, id, player, key)) return;
            json(ex, 200, opsJson(pending.getOrDefault(id, NONE)));

        } else if (post && action.equals("ack")) {
            // Host confirms ops applied to its core — Хост підтверджує, що списання знято з ядра
            if (denyNonHost(ex, id, player, key)) return;
            Set<String> ids = new HashSet<>(Arrays.asList(query(ex.getRequestURI(), "ids").split(",")));
            List<Op> list = pending.get(id);
            if (list != null && list.removeIf(o -> ids.contains(o.id))) saveEconomy();
            send(ex, 200, "");

        } else {
            send(ex, 404, "");
        }
    }

    // ---------- Utils — Утиліти ----------

    static String query(URI uri, String key) {
        String qs = uri.getRawQuery();
        if (qs == null) return "";
        for (String kv : qs.split("&")) {
            int i = kv.indexOf('=');
            if (i > 0 && kv.substring(0, i).equals(key)) {
                try {
                    return URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** Max request body: a sector save is a few MB, the rest is KB — Максимум тіла запиту: сейв сектора — кілька МБ, решта — кілобайти. */
    static final int MAX_BODY = 64 * 1024 * 1024, MAX_INFO = 1024 * 1024, MAX_SMALL = 64 * 1024;

    /** Body limit by path: save 64 MB, info 1 MB, everything else 64 KB — Ліміт тіла за шляхом: сейв 64 МБ, info 1 МБ, решта 64 КБ. */
    static int bodyLimit(String path) {
        if (path.endsWith("/save")) return MAX_BODY;
        if (path.endsWith("/info")) return MAX_INFO;
        return MAX_SMALL;
    }

    // ---------- Flood limits (this PC is exempt) — Обмеження від флуду (цей ПК не обмежується) ----------

    static byte[] readBody(Exchange ex) throws IOException {
        if (ex.req.body.length > MAX_BODY) throw new IOException("request body too large");
        return ex.req.body;
    }

    static void json(Exchange ex, int code, String body) throws IOException {
        sendBytes(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    static void send(Exchange ex, int code, String body) throws IOException {
        sendBytes(ex, code, body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(Exchange ex, int code, byte[] b) throws IOException {
        ex.code = code;
        ex.body = b;
    }

    // ---------- ArcNet glue — Склейка з ArcNet ----------

    /** Runs one request on a pool thread — Виконує один запит у потоці пулу. */
    static void serve(Connection c, int id, byte[] raw, String from) {
        Exchange ex;
        try {
            ex = new Exchange(c, id, AuroraWire.decodeReq(raw), from);
        } catch (Throwable e) {
            Log.warn("Aurora Authority: bad request from @", c);
            c.close(DcReason.error);
            return;
        }
        handle(ex);
    }

    /**
     * One request and its answer. The answer is sent on close(), or later through finish() for a parked /wait.
     * — Один запит і відповідь на нього. Відповідь іде на close(), або пізніше через finish() для запаркованого /wait.
     */
    static final class Exchange {
        final Connection conn;
        final int id;
        final AuroraWire.Req req;
        final InetSocketAddress remote;
        int code = 500;
        byte[] body = new byte[0];
        volatile boolean deferred;
        boolean done;
        /** Password part of the token — Частина токена з паролем. */
        final String password;
        /** The planet host's own game: loopback AND it knows the owner secret — Власна гра хоста планети: loopback І знає секрет власника. */
        final boolean owner;

        Exchange(Connection conn, int id, AuroraWire.Req req, String from) throws UnknownHostException {
            this.conn = conn;
            this.id = id;
            this.req = req;
            // The address captured when the request arrived, not re-read now: a closed link would report none,
            // and treating "none" as this PC let a request skip payment and bans
            // — Адреса, взята, коли запит прийшов, а не перечитана зараз: закрите з'єднання не дало б жодної,
            // а "жодна" як "цей ПК" дозволяла запиту обійти оплату й бани
            this.remote = new InetSocketAddress(InetAddress.getByName(from), 0);
            // token = password [\u0001 owner secret]. Loopback alone is not enough: a tunnel (playit.gg, ngrok...) makes
            // internet players look like 127.0.0.1 too
            // — token = пароль [\u0001 секрет власника]. Самого loopback замало: тунель (playit.gg, ngrok...) робить
            // гравців з інтернету теж схожими на 127.0.0.1
            String t = req.token == null ? "" : req.token;
            int cut = t.indexOf('\u0001');
            this.password = cut < 0 ? t : t.substring(0, cut);
            this.owner = cut >= 0 && isLoopback(plainAddr(remote.getAddress()))
                && java.security.MessageDigest.isEqual(t.substring(cut + 1).getBytes(StandardCharsets.UTF_8), env.ownerSecret().getBytes(StandardCharsets.UTF_8));
        }

        InetSocketAddress getRemoteAddress() {
            return remote;
        }

        /** Path with query, for query() — Шлях із query, для query(). */
        URI getRequestURI() {
            return URI.create(req.path);
        }

        void close() {
            if (!deferred) finish(code, body);
        }

        void finish(int code, byte[] body) {
            synchronized (this) {
                if (done) return;
                done = true;
            }
            try {
                AuroraWire.send(conn, AuroraWire.RESP, id, AuroraWire.encodeResp(code, body));
            } catch (IOException e) {
                Log.warn("Aurora Authority: could not answer @ (@)", conn, e.getMessage());
            }
        }
    }
}
