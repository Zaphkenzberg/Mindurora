package mindustry.game;

import arc.*;

public enum Difficulty{
    //TODO these need tweaks
    casual(0.75f, 0.5f, 2f),
    easy(1f, 0.75f, 1.5f),
    normal(1f, 1f, 1f),
    hard(1.25f, 1.5f, 0.8f),
    eradication(1.5f, 2f, 0.6f),
    // AURORA: harder modes — enemies hit harder and explode on death — Складніші режими: вороги б'ють сильніше й вибухають після смерті
    apocalypse(1.75f, 2.5f, 0.5f, 1.25f, 2.5f, 0.25f, 400f),
    nightmare(2.25f, 3f, 0.4f, 1.5f, 3.5f, 0.4f, 800f);

    public static final Difficulty[] all = values();

    static{
        // AURORA: the three hardest modes get more time before the first wave (x normal) — Три найважчі режими мають більше часу до першої хвилі (x звичайного)
        eradication.prepTimeMultiplier = 1.5f;
        apocalypse.prepTimeMultiplier = 2f;
        nightmare.prepTimeMultiplier = 2.5f;
    }

    /** AURORA: first-wave countdown multiplier; NaN = same as waveTimeMultiplier — Множник відліку до першої хвилі; NaN = як waveTimeMultiplier. */
    public float prepTimeMultiplier = Float.NaN;

    public float prepTime(){
        return Float.isNaN(prepTimeMultiplier) ? waveTimeMultiplier : prepTimeMultiplier;
    }

    //TODO add more fields
    public float enemyHealthMultiplier, enemySpawnMultiplier, waveTimeMultiplier;
    /** AURORA: enemy unit and block damage — Шкода ворожих юнітів і блоків. */
    public float enemyDamageMultiplier = 1f;
    /** AURORA: enemy units explode on death: radius in tiles (0 = off), damage as a share of max health, damage cap
     * — Ворожі юніти вибухають після смерті: радіус у клітинках (0 = вимк.), шкода як частка макс. здоров'я, межа шкоди. */
    public float deathExplosionRadius, deathExplosionShare, deathExplosionCap;

    Difficulty(float enemyHealthMultiplier, float enemySpawnMultiplier, float waveTimeMultiplier){
        this.enemySpawnMultiplier = enemySpawnMultiplier;
        this.waveTimeMultiplier = waveTimeMultiplier;
        this.enemyHealthMultiplier = enemyHealthMultiplier;
    }

    Difficulty(float enemyHealthMultiplier, float enemySpawnMultiplier, float waveTimeMultiplier, float enemyDamageMultiplier,
               float deathExplosionRadius, float deathExplosionShare, float deathExplosionCap){
        this(enemyHealthMultiplier, enemySpawnMultiplier, waveTimeMultiplier);
        this.enemyDamageMultiplier = enemyDamageMultiplier;
        this.deathExplosionRadius = deathExplosionRadius;
        this.deathExplosionShare = deathExplosionShare;
        this.deathExplosionCap = deathExplosionCap;
    }

    /**
     * AURORA: an enemy wave unit died in a campaign sector — blow it up if the difficulty says so.
     * Damage only on the server; it only hurts the player's side (the unit's own team is spared, so no chain reactions).
     * — Ворожий юніт хвилі загинув у секторі кампанії — підірвати, якщо так каже складність.
     * Шкода лише на сервері; б'є тільки сторону гравця (своя команда юніта не страждає, тож ланцюгових вибухів нема).
     */
    public static void onUnitDeath(mindustry.gen.Unit unit){
        var state = mindustry.Vars.state;
        if(unit == null || !state.isCampaign() || unit.team != state.rules.waveTeam) return;
        Difficulty d = state.getPlanet().campaignRules.difficulty;
        if(d.deathExplosionRadius <= 0f) return;

        float radius = d.deathExplosionRadius * mindustry.Vars.tilesize;
        float damage = Math.min(10f + unit.maxHealth * d.deathExplosionShare, d.deathExplosionCap);
        if(!mindustry.Vars.net.client()){
            mindustry.entities.Damage.damage(unit.team, unit.x, unit.y, radius, damage);
        }
        if(!mindustry.Vars.headless){
            (damage > 250f ? mindustry.content.Fx.massiveExplosion : mindustry.content.Fx.blastExplosion).at(unit.x, unit.y);
            mindustry.gen.Sounds.explosion.at(unit.x, unit.y);
        }
    }

    public String info(){
        String res =
        (enemyHealthMultiplier == 1f ? "" : Core.bundle.format("difficulty.enemyHealthMultiplier", percentStat(enemyHealthMultiplier)) + "\n") +
        (enemySpawnMultiplier == 1f ? "" : Core.bundle.format("difficulty.enemySpawnMultiplier", percentStat(enemySpawnMultiplier)) + "\n") +
        (waveTimeMultiplier == 1f ? "" : Core.bundle.format("difficulty.waveTimeMultiplier", percentStatNeg(waveTimeMultiplier)) + "\n") +
        (enemyDamageMultiplier == 1f ? "" : Core.bundle.format("difficulty.enemyDamageMultiplier", percentStat(enemyDamageMultiplier)) + "\n") +
        (deathExplosionRadius <= 0f ? "" : Core.bundle.format("difficulty.deathExplosion", arc.util.Strings.autoFixed(deathExplosionRadius, 1)) + "\n");

        return res.isEmpty() ? Core.bundle.get("difficulty.nomodifiers") : res;
    }

    public String localized(){
        return Core.bundle.get("difficulty." + name());
    }

    static String percentStat(float val){
        return ((int)(val * 100 - 100) > 0 ? "[negstat]+" : "[stat]") + (int)(val * 100 - 100) + "%[]";
    }

    static String percentStatNeg(float val){
        return ((int)(val * 100 - 100) > 0 ? "[stat]+" : "[negstat]") + (int)(val * 100 - 100) + "%[]";
    }
}
