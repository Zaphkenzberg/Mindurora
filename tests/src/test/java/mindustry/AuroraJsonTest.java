package mindustry;

import arc.util.serialization.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/** AuroraJson builds valid JSON that reads back to the same values — AuroraJson будує валідний JSON, що читається назад у ті самі значення. */
public class AuroraJsonTest {

    @Test
    void emptyObjectAndArray() {
        assertEquals("{}", AuroraJson.obj().toString());
        assertEquals("[]", AuroraJson.arr().toString());
    }

    @Test
    void fieldsInOrderWithAllTypes() {
        String s = AuroraJson.obj().put("role", "host").put("retry", 5).put("big", 3_000_000_000L).put("ok", true).toString();
        assertEquals("{\"role\":\"host\",\"retry\":5,\"big\":3000000000,\"ok\":true}", s);
    }

    @Test
    void trickyStringsSurviveARoundTrip() {
        String[] tricky = {"", "a\"b", "back\\slash", "line\nbreak\ttab", "кирилиця ✓", "\u0001ctl", "{not json}", "x,y:z"};
        for (String v : tricky) {
            JsonValue j = new JsonReader().parse(AuroraJson.obj().put(v, v).toString());
            assertEquals(v, j.child.name, "key " + v);
            assertEquals(v, j.child.asString(), "value " + v);
        }
    }

    @Test
    void nullStringBecomesEmpty() {
        assertEquals("{\"k\":\"\"}", AuroraJson.obj().put("k", (String) null).toString());
    }

    @Test
    void nestedAndRaw() {
        AuroraJson ips = AuroraJson.arr().add("10.0.0.1").add("100.64.0.2");
        AuroraJson ops = AuroraJson.arr().add(AuroraJson.obj().put("id", "a").put("amount", 3)).add(7);
        String s = AuroraJson.obj().put("ips", ips).put("ops", ops).putRaw("rules", "{\"x\":1}").toString();
        JsonValue j = new JsonReader().parse(s);
        assertEquals(2, j.get("ips").size);
        assertEquals("100.64.0.2", j.get("ips").get(1).asString());
        assertEquals(3, j.get("ops").get(0).getInt("amount"));
        assertEquals(7, j.get("ops").get(1).asInt());
        assertEquals(1, j.get("rules").getInt("x"));
    }

    @Test
    void toStringIsStableAndFinishes() {
        AuroraJson o = AuroraJson.obj().put("a", 1);
        assertEquals(o.toString(), o.toString());
        assertThrows(IllegalStateException.class, () -> o.put("b", 2));
    }
}
