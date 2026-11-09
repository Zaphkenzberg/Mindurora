package mindustry;

import org.junit.jupiter.api.*;

import java.net.*;
import java.util.*;

import static mindustry.AuthorityClaims.*;
import static mindustry.AuthorityEconomy.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure logic of the Authority: sector host states, claim decision, payment split, join address.
 * No network, disk or game here.
 * — Чиста логіка Authority: стани хоста сектора, рішення при клеймі, розподіл оплати, адреса для входу.
 * Без мережі, диска й гри.
 */
public class AuroraAuthorityTest {

    static Host host(String player, long lastSeen, boolean ready) {
        return new Host("10.0.0.1", 6567, player, lastSeen, ready);
    }

    // ---------- Host states — Стани хоста ----------

    @Test
    void readyHostGoesThroughAllStates() {
        Host h = host("p", 0, true);
        assertEquals(OK, h.stateAt(0));
        assertEquals(OK, h.stateAt(OK_TIMEOUT - 1));
        assertEquals(LOST, h.stateAt(OK_TIMEOUT));
        assertEquals(LOST, h.stateAt(LOST_TIMEOUT - 1));
        assertEquals(ADAPT, h.stateAt(LOST_TIMEOUT));
        assertEquals(ADAPT, h.stateAt(ADAPT_TIMEOUT - 1));
        assertEquals(FREE, h.stateAt(ADAPT_TIMEOUT));
    }

    @Test
    void aliveUntilFree() {
        Host h = host("p", 0, true);
        assertTrue(h.alive(ADAPT_TIMEOUT - 1));
        assertFalse(h.alive(ADAPT_TIMEOUT));
    }

    @Test
    void loadingHostIsFreeAfterPendingTimeout() {
        Host h = host("p", 0, false);
        assertEquals(OK, h.stateAt(PENDING_TIMEOUT - 1));
        assertEquals(FREE, h.stateAt(PENDING_TIMEOUT));
    }

    @Test
    void secondsToFree() {
        assertEquals(ADAPT_TIMEOUT / 1000, host("p", 0, true).secondsToFree(0));
        assertEquals(PENDING_TIMEOUT / 1000, host("p", 0, false).secondsToFree(0));
        assertEquals(1, host("p", 0, true).secondsToFree(ADAPT_TIMEOUT - 500));
        // never 0 or negative: the client waits at least a second — ніколи не 0 і не мінус: клієнт чекає хоча б секунду
        assertEquals(1, host("p", 0, true).secondsToFree(ADAPT_TIMEOUT * 10));
    }

    @Test
    void beatMakesReadyAndTouchDoesNot() {
        Host h = host("p", 0, false).keyed("k");
        Host t = h.touch(5000);
        assertFalse(t.ready);
        assertEquals(5000, t.lastSeen);
        assertEquals("k", t.key);
        Host b = h.beat(7000);
        assertTrue(b.ready);
        assertEquals(7000, b.lastSeen);
        assertEquals("k", b.key);
    }

    // ---------- Claim decision — Рішення при клеймі ----------

    @Test
    void freeSectorGoesToClaimer() {
        Host mine = host("me", 1000, false);
        assertSame(mine, pickHost(null, mine, "me", "", 1000));
    }

    @Test
    void deadHostIsReplaced() {
        Host old = host("other", 0, true).keyed("x");
        Host mine = host("me", ADAPT_TIMEOUT, false);
        assertSame(mine, pickHost(old, mine, "me", "", ADAPT_TIMEOUT));
    }

    @Test
    void loadingHostThatNeverBeatIsReplacedAfterPendingTimeout() {
        Host old = host("other", 0, false).keyed("x");
        Host mine = host("me", PENDING_TIMEOUT, false);
        assertSame(mine, pickHost(old, mine, "me", "", PENDING_TIMEOUT));
    }

    @Test
    void liveHostOfSomeoneElseStays() {
        Host old = host("other", 0, true).keyed("x");
        Host mine = host("me", 1000, false);
        assertSame(old, pickHost(old, mine, "me", "x", 1000)); // even with their key — навіть з їхнім ключем
    }

    @Test
    void sameHostWithItsKeyComesBackAndKeepsTheKey() {
        Host old = host("me", 0, true).keyed("secret");
        Host mine = new Host("192.168.1.9", 7000, "me", 1000, false).keyed("new");
        Host cur = pickHost(old, mine, "me", "secret", 1000);
        assertNotSame(old, cur);
        assertEquals("secret", cur.key);
        assertEquals("192.168.1.9", cur.ip);
        assertEquals(7000, cur.port);
    }

    @Test
    void sameUuidWithoutTheKeyWaits() {
        Host old = host("me", 0, true).keyed("secret");
        Host mine = host("me", 1000, false);
        assertSame(old, pickHost(old, mine, "me", "wrong", 1000));
        assertSame(old, pickHost(old, mine, "me", "", 1000));
    }

    @Test
    void emptyKeyNeverMatches() {
        Host old = host("me", 0, true); // no key — без ключа
        Host mine = host("me", 1000, false);
        assertSame(old, pickHost(old, mine, "me", "", 1000));
    }

    // ---------- Payment split — Розподіл оплати ----------

    static Map<String, Integer> map(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    static Map<String, Map<String, Integer>> avail(String item, Map<String, Integer> per) {
        Map<String, Map<String, Integer>> a = new LinkedHashMap<>();
        a.put(item, per);
        return a;
    }

    static void assertValidSplit(Map<String, Integer> need, Map<String, Map<String, Integer>> avail, Map<String, Map<String, Integer>> out) {
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            long sum = 0;
            for (Map.Entry<String, Integer> s : out.get(e.getKey()).entrySet()) {
                assertTrue(s.getValue() > 0, "takes are positive");
                assertTrue(s.getValue() <= avail.get(e.getKey()).get(s.getKey()), "a sector never gives more than it has");
                sum += s.getValue();
            }
            assertEquals((long) e.getValue(), sum, "takes add up to the price of " + e.getKey());
        }
    }

    @Test
    void oneSectorPaysAll() {
        Map<String, Integer> need = map("copper", 100);
        Map<String, Map<String, Integer>> a = avail("copper", map("s1", 500));
        Map<String, Map<String, Integer>> out = splitSpend(need, a);
        assertEquals(map("s1", 100), out.get("copper"));
    }

    @Test
    void splitIsProportional() {
        Map<String, Integer> need = map("copper", 100);
        Map<String, Map<String, Integer>> a = avail("copper", map("s1", 300, "s2", 100));
        Map<String, Map<String, Integer>> out = splitSpend(need, a);
        assertEquals(map("s1", 75, "s2", 25), out.get("copper"));
    }

    @Test
    void exactTotalEmptiesEverySector() {
        Map<String, Integer> need = map("lead", 400);
        Map<String, Map<String, Integer>> a = avail("lead", map("s1", 150, "s2", 250));
        Map<String, Map<String, Integer>> out = splitSpend(need, a);
        assertEquals(map("s1", 150, "s2", 250), out.get("lead"));
    }

    @Test
    void roundingNeverOverpays() {
        Map<String, Integer> need = map("silicon", 2);
        Map<String, Map<String, Integer>> a = avail("silicon", map("s1", 1, "s2", 1, "s3", 1));
        Map<String, Map<String, Integer>> out = splitSpend(need, a);
        assertValidSplit(need, a, out);
    }

    @Test
    void severalItemsAreSplitIndependently() {
        Map<String, Integer> need = map("copper", 10, "lead", 6);
        Map<String, Map<String, Integer>> a = new LinkedHashMap<>();
        a.put("copper", map("s1", 10, "s2", 10));
        a.put("lead", map("s2", 6));
        Map<String, Map<String, Integer>> out = splitSpend(need, a);
        assertValidSplit(need, a, out);
        assertEquals(map("s2", 6), out.get("lead"));
    }

    @Test
    void hugeStocksDoNotOverflow() {
        // two sectors with 2 billion each: the int total used to overflow and nothing was taken
        // — два сектори по 2 мільярди: сума в int переповнювалась, і нічого не знімалось
        Map<String, Integer> need = map("copper", 2_000_000_000);
        Map<String, Map<String, Integer>> a = avail("copper", map("s1", 2_000_000_000, "s2", 2_000_000_000));
        assertValidSplit(need, a, splitSpend(need, a));
    }

    @Test
    void randomSplitsKeepTheRules() {
        Random r = new Random(152);
        for (int round = 0; round < 2000; round++) {
            Map<String, Integer> per = new LinkedHashMap<>();
            int sectors = 1 + r.nextInt(8);
            long total = 0;
            for (int i = 0; i < sectors; i++) {
                int a = 1 + r.nextInt(r.nextBoolean() ? 10 : 100_000);
                per.put("s" + i, a);
                total += a;
            }
            Map<String, Integer> need = map("copper", 1 + r.nextInt((int) Math.min(total, Integer.MAX_VALUE)));
            Map<String, Map<String, Integer>> a = avail("copper", per);
            assertValidSplit(need, a, splitSpend(need, a));
        }
    }

    // ---------- Join address — Адреса для входу ----------

    static InetAddress addr(String s) throws UnknownHostException {
        return InetAddress.getByName(s); // literals only: no DNS lookup — лише літерали: без DNS
    }

    @Test
    void joinerGetsTheAddressOnHisOwnNetwork() throws Exception {
        Host h = host("p", 0, true).ips(new String[]{"192.168.1.7", "10.5.5.5"});
        assertEquals("192.168.1.7", h.ipFor(addr("192.168.1.50")));
        assertEquals("10.5.5.5", h.ipFor(addr("10.5.5.9")));
    }

    @Test
    void unknownNetworkFallsBackToMainAddress() throws Exception {
        Host h = host("p", 0, true).ips(new String[]{"192.168.1.7"});
        assertEquals("10.0.0.1", h.ipFor(addr("172.16.0.3")));
        assertEquals("10.0.0.1", h.ipFor(null));
        assertEquals("10.0.0.1", h.ipFor(addr("::1")));
        assertEquals("10.0.0.1", host("p", 0, true).ipFor(addr("192.168.1.50"))); // no extra addresses — без додаткових адрес
    }
}
