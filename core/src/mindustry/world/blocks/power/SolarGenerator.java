package mindustry.world.blocks.power;

import arc.math.*;
import arc.struct.*;
import mindustry.world.meta.*;

import static mindustry.Vars.*;

public class SolarGenerator extends PowerGenerator{

    public SolarGenerator(String name){
        super(name);
        //vanilla removed BlockFlag.generator so enemies target solar panels last.
        //AURORA: the flag is back for ulocate, so enemy AI now treats solar like any generator
        // — ваніла прибирала generator, щоб вороги били сонячні панелі останніми; AURORA повертає його для ulocate, тож ШІ ворогів тепер б'є їх як звичайні генератори
        envEnabled = Env.any;
        // AURORA: ulocate filter "generator" — фільтр ulocate "генератор"
        flags = arc.struct.EnumSet.of(BlockFlag.generator);
    }

    @Override
    public void setStats(){
        super.setStats();
        stats.remove(generationType);
        stats.add(generationType, powerProduction * 60.0f, StatUnit.powerSecond);
    }

    public class SolarGeneratorBuild extends GeneratorBuild{
        @Override
        public void updateTile(){
            productionEfficiency = enabled ?
                state.rules.solarMultiplier * Mathf.maxZero(Attribute.light.env() +
                    (state.rules.lighting ?
                        1f - state.rules.ambientLight.a :
                        1f
                    )) : 0f;
        }
    }
}
