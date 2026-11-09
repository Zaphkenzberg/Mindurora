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

import static mindustry.AuthorityRoutes.Exchange;

/**
 * Authority internals: who may talk to the Authority: this PC, LAN and Tailscale only (never the internet), and how often (flood limits).
 * — Нутрощі Authority: хто може говорити з Authority: лише цей ПК, LAN і Tailscale (ніколи інтернет), і як часто (обмеження від флуду).
 */
final class AuthorityAccess {

    private AuthorityAccess() {
    }

    // ---------- Who may connect: this PC, LAN, Tailscale; never the internet — Хто може підключитись: цей ПК, LAN, Tailscale; інтернет — ніколи ----------

    /** Tailscale: 100.64.0.0/10 and fd7a:115c:a1e0::/48 — Tailscale: 100.64.0.0/10 і fd7a:115c:a1e0::/48. */
    static boolean isTailscale(InetAddress a) {
        byte[] b = a.getAddress();
        // 100.64.0.0/10 is also the ISP carrier-grade NAT range: trust it only if this PC really is on Tailscale
        // — 100.64.0.0/10 — це ще й діапазон CGNAT провайдерів: довіряємо, лише якщо цей ПК справді в Tailscale
        if (b.length == 4) return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64 && tailscaleHere();
        return b.length == 16 && (b[0] & 0xff) == 0xfd && (b[1] & 0xff) == 0x7a && (b[2] & 0xff) == 0x11 && (b[3] & 0xff) == 0x5c
            && (b[4] & 0xff) == 0xa1 && (b[5] & 0xff) == 0xe0;
    }

    static volatile boolean tailscaleCached;

    static volatile long tailscaleCheckedAt;

    /** This PC has a Tailscale (100.64.0.0/10) address; rechecked every 30 s — Цей ПК має адресу Tailscale (100.64.0.0/10); перевірка раз на 30 с. */
    static boolean tailscaleHere() {
        long now = System.currentTimeMillis();
        if (now - tailscaleCheckedAt > 30_000L) {
            boolean found = false;
            for (String ip : ownIps()) {
                String[] p = ip.split("\\.");
                if (p.length == 4 && p[0].equals("100")) {
                    int second = Strings.parseInt(p[1], -1);
                    if (second >= 64 && second <= 127) found = true;
                }
            }
            tailscaleCached = found;
            tailscaleCheckedAt = now;
        }
        return tailscaleCached;
    }

    /** Private networks: 10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA — Приватні мережі: 10/8, 172.16/12, 192.168/16, link-local, IPv6 ULA. */
    static boolean isLan(InetAddress a) {
        if (a.isSiteLocalAddress() || a.isLinkLocalAddress()) return true;
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    /** Allow a request from this address? Public internet never — Чи пускати запит з цієї адреси? Інтернет — ніколи. */
    static boolean allowed(InetAddress a) {
        if (a.isLoopbackAddress()) return true;
        // IPv4 wrapped in IPv6 (::ffff:x.x.x.x) — IPv4, загорнута в IPv6
        if (a instanceof Inet6Address) {
            byte[] b = a.getAddress();
            boolean mapped = (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
            for (int i = 0; i < 10 && mapped; i++) if (b[i] != 0) mapped = false;
            if (mapped) {
                try {
                    a = InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]});
                } catch (Exception ignored) {
                }
            }
        }
        return isLan(a) || isTailscale(a);
    }

    // ---------- Planet bans — Блокування на планеті ----------

    /** IPv4 text even for IPv4-mapped IPv6 — Текст IPv4 навіть для IPv4, загорнутої в IPv6. */
    static String plainAddr(InetAddress a) {
        String s = a.getHostAddress();
        return s.startsWith("::ffff:") ? s.substring(7) : s;
    }

    /** IPv4 addresses of this PC (LAN, Tailscale) — IPv4-адреси цього ПК (LAN, Tailscale). */
    static List<String> ownIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!n.isUp()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // ---------- Research cost — Ціна досліджень ----------

    /** The planet host's own game (loopback) — Власна гра хоста планети (loopback). */
    static boolean fromThisPc(Exchange ex) {
        return ex.owner;
    }

    // ---------- Utils — Утиліти ----------

    /** An IP literal (no name lookup) in loopback / LAN / Tailscale — IP-літерал (без DNS) з loopback / LAN / Tailscale. */
    static boolean privateLiteral(String ip) {
        if (ip == null || ip.isEmpty() || ip.length() > 45 || !ip.matches("[0-9a-fA-F:.]+")) return false;
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a.isLoopbackAddress() || isLan(a) || isTailscale(a);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Flood limits (this PC is exempt) — Обмеження від флуду (цей ПК не обмежується) ----------

    static final int MAX_LINKS_PER_IP = 4, MAX_LINKS = 64;

    /** Requests per second per address, and the burst allowed — Запитів на секунду з однієї адреси і дозволений сплеск. */
    static final float RATE = 30f, BURST = 90f;

    static final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> linksPerIp = new ConcurrentHashMap<>();

    /** ip -> {tokens, last refill ms} — ip -> {токени, останнє поповнення мс} */
    static final ConcurrentHashMap<String, double[]> buckets = new ConcurrentHashMap<>();

    static final ConcurrentHashMap<Connection, String> connIp = new ConcurrentHashMap<>();

    /** Bytes of unfinished requests across all links, and the cap — Байти незавершених запитів на всіх з'єднаннях і межа. */
    static final java.util.concurrent.atomic.AtomicLong assembling = new java.util.concurrent.atomic.AtomicLong();

    static final long MAX_ASSEMBLING = 160L * 1024 * 1024;

    static String ipOf(Connection c) {
        InetSocketAddress a = c.getRemoteAddressTCP();
        return a == null ? "" : plainAddr(a.getAddress());
    }

    /** An unknown (empty) address is never "this PC" — Невідома (порожня) адреса ніколи не "цей ПК". */
    static boolean isLoopback(String ip) {
        return ip != null && (ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1"));
    }

    static int totalLinks() {
        int n = 0;
        for (var v : linksPerIp.values()) n += v.get();
        return n;
    }

    /** Token bucket per address — Відро токенів на адресу. */
    static boolean allowRate(String ip) {
        double[] b = buckets.computeIfAbsent(ip, k -> new double[]{BURST, System.currentTimeMillis()});
        synchronized (b) {
            long now = System.currentTimeMillis();
            b[0] = Math.min(BURST, b[0] + (now - b[1]) / 1000.0 * RATE);
            b[1] = now;
            if (b[0] < 1) return false;
            b[0] -= 1;
            return true;
        }
    }
}
