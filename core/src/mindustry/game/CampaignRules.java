package mindustry.game;

import mindustry.*;
import mindustry.gen.*;
import mindustry.type.*;

public class CampaignRules{
    public Difficulty difficulty = Difficulty.normal;
    public boolean fog;
    public boolean showSpawns;
    public boolean sectorInvasion;
    public boolean randomWaveAI;
    public boolean legacyLaunchPads;
    public boolean rtsAI;
    public boolean clearSectorOnLose;
    /** AURORA: 0 = planet light, 1 = always night, 2 = day/night cycle — 0 = світло планети, 1 = завжди ніч, 2 = цикл день/ніч. */
    public int dayNight = 0;

    public void apply(Planet planet, Rules rules){
        rules.staticFog = rules.fog = fog;
        rules.showSpawns = showSpawns;
        rules.randomWaveAI = randomWaveAI;
        rules.objectiveTimerMultiplier = difficulty.waveTimeMultiplier;
        rules.dayNightMode = dayNight; // AURORA
        if(planet.showRtsAIRule){
            boolean enabled = rtsAI && rules.attackMode; //if attackMode becomes false (waves enabled), turn off RTS AI
            boolean swapped = rules.teams.get(rules.waveTeam).rtsAi != enabled;

            rules.teams.get(rules.waveTeam).rtsAi = enabled;
            rules.teams.get(rules.waveTeam).rtsMaxSquad = 15;

            if(swapped && Vars.state.isGame()){
                Groups.unit.each(u -> {
                    if(u.team == rules.waveTeam && !u.isPlayer()){
                        u.resetController();
                    }
                });
            }
        }
        rules.teams.get(rules.waveTeam).blockHealthMultiplier = difficulty.enemyHealthMultiplier;
        rules.teams.get(rules.waveTeam).unitHealthMultiplier = difficulty.enemyHealthMultiplier;
        rules.teams.get(rules.waveTeam).unitCostMultiplier = 1f / difficulty.enemySpawnMultiplier;
        rules.teams.get(rules.waveTeam).unitBuildSpeedMultiplier = difficulty.enemySpawnMultiplier;
        // AURORA: harder modes hit harder — Складніші режими б'ють сильніше
        rules.teams.get(rules.waveTeam).unitDamageMultiplier = difficulty.enemyDamageMultiplier;
        rules.teams.get(rules.waveTeam).blockDamageMultiplier = difficulty.enemyDamageMultiplier;
    }
}
