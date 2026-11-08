package mindustry;
import arc.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.gen.*;

import static mindustry.Vars.*;
import static mindustry.AuroraNet.get;
import static mindustry.AuroraNet.parse;
import mindustry.AuroraNet.Res;

public class AuroraRules {
    // ---------- Shared campaign rules — Спільні правила кампанії ----------

    /** A guest plays on someone else's planet: campaign rules come from that planet's host — Гість грає на чужій планеті: правила кампанії приходять від хоста планети. */
    public static boolean rulesFromHost() {
        return campaignSlot == slotGuest;
    }

    /** Pulls the planet host's campaign rules (guest only) and applies them — Тягне правила кампанії хоста планети (лише гість) і застосовує їх. */
    public static void syncRules() {
        if (!rulesFromHost()) return;
        mainExecutor.submit(AuroraRules::syncRulesNow);
    }

    /** Blocking fetch, result applied on the main thread — Блокуючий запит, результат застосовується в головному потоці. */
    static void syncRulesNow() {
        if (!rulesFromHost()) return;
        try {
            Res res = get("/rules");
            if (res.code != 200) return; // old Authority without /rules — старий Authority без /rules
            JsonValue root = parse(res);
            Core.app.post(() -> applyRules(root));
        } catch (Throwable t) {
            Log.err("syncRules failed", t);
        }
    }

    static void applyRules(JsonValue root) {
        if (!rulesFromHost()) return;
        for (JsonValue c = root.child; c != null; c = c.next) {
            mindustry.type.Planet pl = content.planet(c.name);
            if (pl == null) continue;
            mindustry.game.CampaignRules remote;
            try {
                remote = mindustry.io.JsonIO.readRemote(mindustry.game.CampaignRules.class, c); // no class tags — без тегів класів
            } catch (Throwable ex) {
                continue; // unknown fields / values (other version) — невідомі поля / значення (інша версія)
            }
            if (remote == null || remote.difficulty == null) continue;
            String was = mindustry.io.JsonIO.write(pl.campaignRules);
            if (was.equals(mindustry.io.JsonIO.write(remote))) continue;
            Log.info("Aurora: @ campaign rules from planet host (difficulty @)", pl.name, remote.difficulty);
            // Copy into the same object: an open rules dialog keeps showing it — Копіюємо в той самий об'єкт: відкритий діалог правил і далі його показує
            mindustry.io.JsonIO.copy(remote, pl.campaignRules);
            pl.saveRules();
            // We host a sector of this planet: apply now and tell our clients — Ми хостимо сектор цієї планети: застосувати зараз і сказати клієнтам
            if (state.isGame() && state.isCampaign() && state.getPlanet() == pl && !net.client()) {
                pl.campaignRules.apply(pl, state.rules);
                if (net.server()) Call.setRules(state.rules);
            }
        }
    }

}