package mindustry.ui.dialogs;

import arc.*;
import arc.func.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import mindustry.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.ui.*;

public class CampaignRulesDialog extends BaseDialog{
    Planet planet;
    Table current;

    public CampaignRulesDialog(){
        super("@campaign.difficulty");

        addCloseButton();

        hidden(() -> {
            if(planet != null){
                planet.saveRules();

                if(Vars.state.isGame() && Vars.state.isCampaign() && Vars.state.getPlanet() == planet){
                    planet.campaignRules.apply(planet, Vars.state.rules);
                    Call.setRules(Vars.state.rules);
                }
            }
        });

        onResize(() -> {
            rebuild();
        });
    }

    void rebuild(){
        CampaignRules rules = planet.campaignRules;
        cont.clear();

        cont.top().pane(inner -> {
            inner.top().left().defaults().fillX().left().pad(5);
            current = inner;

            current.table(Tex.button, t -> {
                t.margin(10f);
                var group = new ButtonGroup<>();
                var style = Styles.flatTogglet;

                t.defaults().size(140f, 50f);

                for(Difficulty diff : Difficulty.all){
                    // Aurora: only Eradication..Nightmare — Aurora: лише Викорінювальна..Кошмар
                    if(Vars.auroraSlot() && diff.ordinal() < Difficulty.eradication.ordinal()) continue;
                    // Guest: the planet host decides — Гість: вирішує хост планети
                    boolean locked = mindustry.AuroraNet.rulesFromHost();
                    t.button(diff.localized(), style, () -> {
                        if(!locked) rules.difficulty = diff;
                    }).group(group).checked(b -> rules.difficulty == diff).disabled(b -> locked && rules.difficulty != diff)
                    .tooltip(diff.info());

                    if(Core.graphics.isPortrait() && diff.ordinal() % 2 == 1){
                        t.row();
                    }
                }
            }).left().fill(false).expand(false, false).row();
            if(mindustry.AuroraNet.rulesFromHost()) current.add(mindustry.AuroraNet.tr("rules.fromhost")).left().row();

            if(planet.allowSectorInvasion){
                check("@rules.invasions", b -> rules.sectorInvasion = b, () -> rules.sectorInvasion);
            }

            check("@rules.fog", b -> rules.fog = b, () -> rules.fog);
            check("@rules.showspawns", b -> rules.showSpawns = b, () -> rules.showSpawns);
            check("@rules.randomwaveai", b -> rules.randomWaveAI = b, () -> rules.randomWaveAI);

            // AURORA: light mode — planet / always night / day-night cycle — Режим світла: як на планеті / завжди ніч / день і ніч
            current.table(Tex.button, t -> {
                t.margin(10f);
                var group = new ButtonGroup<>();
                t.defaults().size(150f, 50f);
                String[] names = {"@rules.daynight.planet", "@rules.daynight.night", "@rules.daynight.cycle"};
                for(int i = 0; i < names.length; i++){
                    int mode = i;
                    boolean locked = mindustry.AuroraNet.rulesFromHost();
                    t.button(names[i], Styles.flatTogglet, () -> {
                        if(!locked) rules.dayNight = mode;
                    }).group(group).checked(b -> rules.dayNight == mode).disabled(b -> locked && rules.dayNight != mode);
                }
            }).left().fill(false).expand(false, false).row();

            if(planet.showRtsAIRule){
                check("@rules.rtsai.campaign", b -> rules.rtsAI = b, () -> rules.rtsAI);
            }

            if(!planet.clearSectorOnLose){
                check("@rules.clearsectoronloss", b -> rules.clearSectorOnLose = b, () -> rules.clearSectorOnLose);
            }

            //TODO: this is intentionally hidden until the new mechanics have been well-tested. I don't want people immediately switching to the old mechanics
            if(planet.allowLegacyLaunchPads){
            //    check("@rules.legacylaunchpads", b -> rules.legacyLaunchPads = b, () -> rules.legacyLaunchPads);
            }
        }).growY();
    }

    public void show(Planet planet){
        this.planet = planet;

        rebuild();
        show();
    }

    void check(String text, Boolc cons, Boolp prov){
        check(text, cons, prov, () -> true);
    }

    void check(String text, Boolc cons, Boolp prov, Boolp condition){
        String infoText = text.substring(1) + ".info";
        // Guest: read-only, follows the planet host's value — Гість: лише перегляд, слідує за значенням хоста планети
        boolean locked = mindustry.AuroraNet.rulesFromHost();
        var cell = current.check(text, b -> {
            if(!locked) cons.get(b);
        }).checked(prov.get()).update(a -> {
            a.setDisabled(locked || !condition.get());
            if(locked) a.setChecked(prov.get());
        });
        if(Core.bundle.has(infoText)){
            cell.tooltip(text + ".info");
        }
        cell.get().left();
        current.row();
    }

}
