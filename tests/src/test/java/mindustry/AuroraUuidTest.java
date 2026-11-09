package mindustry;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sector server knows a player by con.uuid (uuid + CRC32, 16 bytes); Aurora knows him by the bare uuid (8 bytes).
 * — Сервер сектора знає гравця за con.uuid (uuid + CRC32, 16 байтів); Aurora — за голим uuid (8 байтів).
 */
public class AuroraUuidTest {
    // A real pair from a game log: con.uuid on the sector server and the same player's planet uuid
    // — Справжня пара з логу гри: con.uuid на сервері сектора і uuid планети того самого гравця
    static final String CON = "TAIHQoLV2MUAAAAA3UTSMA==", BARE = "TAIHQoLV2MU=";

    @Test
    void connectionUuidBecomesPlanetUuid() {
        assertEquals(BARE, AuroraUtil.planetUuid(CON));
    }

    @Test
    void planetUuidStaysAsItIs() {
        assertEquals(BARE, AuroraUtil.planetUuid(BARE));
        assertEquals(BARE, AuroraUtil.planetUuid(AuroraUtil.planetUuid(CON))); // idempotent — повторне застосування нічого не міняє
    }

    @Test
    void otherTextIsUntouched() {
        assertEquals("", AuroraUtil.planetUuid(null));
        assertEquals("", AuroraUtil.planetUuid(""));
        assertEquals("P1", AuroraUtil.planetUuid("P1"));
        assertEquals("not base64 !!", AuroraUtil.planetUuid("not base64 !!"));
        // 16 bytes whose tail is not the CRC of the head: not a con.uuid — 16 байтів, де хвіст не CRC голови: це не con.uuid
        assertEquals("AAAAAAAAAAAAAAAAAAAAAA==", AuroraUtil.planetUuid("AAAAAAAAAAAAAAAAAAAAAA=="));
    }

    @Test
    void kickByConnectionUuidStopsClaimsByPlanetUuid() {
        try {
            AuroraAuthority.kick(CON);
            assertTrue(AuthorityBans.removed(BARE), "the claim (bare uuid) must be refused");
            assertTrue(AuthorityBans.removed(CON));
            assertTrue(AuthorityBans.removedJson(BARE).contains("kicked"));
        } finally {
            AuthorityBans.kickedUntil.clear();
        }
    }
}
