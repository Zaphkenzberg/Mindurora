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

/**
 * Authority internals: planet campaign rules set by the planet host.
 * — Нутрощі Authority: правила кампанії планети, які задає хост планети.
 */
final class AuthorityRules {

    private AuthorityRules() {
    }

    // ---------- Planet bans — Блокування на планеті ----------

    /** Own Json: JsonIO.json is shared with the main thread and not thread-safe — Свій Json: JsonIO.json спільний з головним потоком і не потокобезпечний. */
    static final arc.util.serialization.Json rulesJsonWriter = new arc.util.serialization.Json(arc.util.serialization.JsonWriter.OutputType.json);

    /** Campaign rules of every planet from the host's own Aurora rules (c2- keys), difficulty clamped to Eradication+
     * — Правила кампанії кожної планети з власних правил Aurora хоста (ключі c2-), складність не нижче Викорінювальної. */
    static String rulesJson() {
        // Same lock as before the split (AuroraAuthority.class): these writes must not interleave with each other
        // — Той самий замок, що й до поділу (AuroraAuthority.class): ці записи не мають перемішуватись між собою
        synchronized (AuroraAuthority.class) {
            AuroraJson o = AuroraJson.obj();
            for (mindustry.type.Planet pl : Vars.content.planets()) {
                if (pl.sectors.isEmpty() || !pl.allowCampaignRules) continue;
                // Raw bytes + our own Json: Core.settings.getJson would use JsonIO.json, which belongs to the main thread
                // — Сирі байти + власний Json: Core.settings.getJson використав би JsonIO.json, який належить головному потоку
                mindustry.game.CampaignRules r = null;
                try {
                    byte[] raw = Core.settings.getBytes("c2-" + pl.name + "-campaign-rules");
                    if (raw != null) r = rulesJsonWriter.readValue(mindustry.game.CampaignRules.class, new UBJsonReader().parse(new ByteArrayInputStream(raw)));
                } catch (Throwable ignored) {
                }
                if (r == null) {
                    // The planet's defaults, copied through our own Json — Типові правила планети, скопійовані через власний Json
                    r = rulesJsonWriter.fromJson(mindustry.game.CampaignRules.class, rulesJsonWriter.toJson(pl.campaignRuleDefaults, mindustry.game.CampaignRules.class));
                    r.sectorInvasion = pl.allowSectorInvasion;
                }
                if (r.difficulty == null || r.difficulty.ordinal() < mindustry.game.Difficulty.eradication.ordinal()) r.difficulty = mindustry.game.Difficulty.eradication;
                o.putRaw(pl.name, rulesJsonWriter.toJson(r, mindustry.game.CampaignRules.class));
            }
            return o.toString();
        }
    }
}
