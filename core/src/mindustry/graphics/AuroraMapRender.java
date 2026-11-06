package mindustry.graphics;

import arc.util.*;
import mindustry.*;
import arc.graphics.*;
import arc.math.*;
import arc.struct.*;
import mindustry.graphics.g3d.*;
import mindustry.type.*;
import mindustry.game.*;

public class AuroraMapRender {

    // ---------- Map colors, change them here only — Кольори карт, міняти лише тут ----------

    /** Base frame (anyone's captured sector), menu and campaign map — Рамка бази (захоплений сектор будь-кого), меню й карта кампанії. */
    public static final Color baseColor = new Color(Team.sharded.color);
    /** Ally color (other players' live hosts) on maps — Колір союзників (живі хости інших гравців) на картах. */
    public static final Color allyColor = Color.valueOf("5aa7ff");
    /** Attack on an enemy base — Атака на ворожу базу. */
    public static final Color attackColor = new Color(Team.crux.color);
    /** Shield and cage on the menu background — Щит і клітка на фоні меню. */
    public static final Color shieldColor = new Color(Team.sharded.color), cageColor = new Color(mindustry.graphics.Pal.accent);

    public static void resetFade() {
        blendValue.clear();
        blendTime.clear();}
    /**
     * Marks for live sectors (menu background and campaign map); the normal base frame is drawn before this.
     * Host on a captured base: blue frame. Someone capturing (waves running / no base yet): blinking blue, orange frame stays.
     * Someone on an enemy base (attack): blinking red.
     * — Позначки живих секторів (фон меню й карта кампанії); звичайна рамка бази малюється до цього.
     * Хост на захопленій базі: синя рамка. Хтось захоплює (хвилі / бази ще нема): блимає синім, помаранчева рамка лишається.
     * Хтось на ворожій базі (атака): блимає червоним.
     */
    public static void drawLiveSector(PlanetRenderer planets, Sector sec, boolean base, boolean waves, boolean attack, float alpha) {
        float[] w = weights(sec, base, waves, attack);
        int o = sec.id * 3;
        float blue = w[o], capture = w[o + 1], atk = w[o + 2];
        if (blue <= 0f && capture <= 0f && atk <= 0f) return;
        Color c = Tmp.c1;

        // Every state fades in / out over FADE_TICKS, so switching between them is smooth — Кожен стан плавно з'являється / зникає за FADE_TICKS, тож перемикання між ними плавне
        if (atk > 0f) {
            // Attack: fast red blinking, red edges, red runner — Атака: швидке червоне блимання, червоні краї, червоний бігунок
            float pulse = Mathf.absin(Time.time, 7f, 1f);
            planets.fill(sec, c.set(attackColor).a(alpha * atk * (0.15f + 0.35f * pulse)), -0.001f);
            planets.drawBorders(sec, attackColor, alpha * atk * (0.5f + 0.5f * pulse));
            drawRunner(planets, sec, alpha * atk, attackColor);
        }
        if (capture > 0f) {
            // Not captured yet (waves still running): calm red fill breathing once per second + red runner
            // — Ще не захоплений (хвилі ще йдуть): спокійна червона заливка, що "дихає" раз на секунду, + червоний бігунок
            float breath = 0.5f + 0.5f * Mathf.sin((float) (Time.globalTime / 60.0 * Mathf.PI2));
            planets.fill(sec, c.set(attackColor).a(alpha * capture * (0.08f + 0.22f * breath)), -0.001f);
            drawRunner(planets, sec, alpha * capture, attackColor);
        }
        if (blue > 0f) {
            // Captured base with a player: blue runner (the frame itself turns blue in frameColor) — Захоплена база з гравцем: синій бігунок (сама рамка синіє в frameColor)
            drawRunner(planets, sec, alpha * blue, allyColor);
        }
    }

    /** State fade time, ticks (0.5 s) — Час переходу між станами, тіки (0.5 с). */
    static final float FADE_TICKS = 30f;
    /** Per planet: 3 weights per sector (blue, capturing, attack) and the last update time — На планету: 3 ваги на сектор (синій, захоплення, атака) і час останнього оновлення. */
    static final ObjectMap<mindustry.type.Planet, float[]> blendValue = new ObjectMap<>();
    static final ObjectMap<mindustry.type.Planet, double[]> blendTime = new ObjectMap<>();
    static final Color frameTmp = new Color();

    /** Sector not fully captured yet: no base, or waves still running — Сектор ще не повністю захоплений: бази нема, або хвилі ще йдуть. */
    public static boolean capturing(boolean base, boolean waves) {
        return !base || waves;
    }

    /**
     * Moves the three state weights of a sector toward their targets and returns the planet's weight array
     * (index sector.id * 3 + 0 blue, 1 capturing, 2 attack). Exactly one target is 1 while someone plays, none otherwise.
     * The first call for a sector (map just opened) jumps straight to the targets. Main thread only.
     * — Рухає три ваги станів сектора до цілей і повертає масив ваг планети
     * (індекс sector.id * 3 + 0 синій, 1 захоплення, 2 атака). Поки хтось грає, рівно одна ціль = 1, інакше жодна.
     * Перший виклик для сектора (карту щойно відкрили) одразу стає цільовим. Лише головний потік.
     */
    static float[] weights(Sector sec, boolean base, boolean waves, boolean attack) {
        float[] v = blendValue.get(sec.planet);
        double[] last = blendTime.get(sec.planet);
        if (v == null || v.length < (sec.id + 1) * 3) {
            int size = Math.max(sec.planet.sectors.size, sec.id + 1);
            blendValue.put(sec.planet, v = new float[size * 3]);
            blendTime.put(sec.planet, last = new double[size]);
        }
        double now = Time.globalTime;
        float dt = (float) (now - last[sec.id]);
        if (last[sec.id] == 0 || dt < 0 || dt > 120f) dt = FADE_TICKS; // first look / long gap: no fade — перший погляд / довга перерва: без переходу
        last[sec.id] = now;

        boolean live = AuroraNet.liveSector(sec);
        boolean atk = live && (attack || (!base && sec.hasEnemyBase()));
        boolean cap = live && !atk && capturing(base, waves);
        boolean blue = live && !atk && !cap;
        float step = dt / FADE_TICKS;
        int o = sec.id * 3;
        v[o] = Mathf.approach(v[o], blue ? 1f : 0f, step);
        v[o + 1] = Mathf.approach(v[o + 1], cap ? 1f : 0f, step);
        v[o + 2] = Mathf.approach(v[o + 2], atk ? 1f : 0f, step);
        return v;
    }

    static final arc.math.geom.Vec3 runCenter = new arc.math.geom.Vec3(), runO1 = new arc.math.geom.Vec3(), runO2 = new arc.math.geom.Vec3(),
            runI1 = new arc.math.geom.Vec3(), runI2 = new arc.math.geom.Vec3(), runA0 = new arc.math.geom.Vec3(), runA1 = new arc.math.geom.Vec3(),
            runB0 = new arc.math.geom.Vec3(), runB1 = new arc.math.geom.Vec3();
    /** Runner speed (edges per second), tail length (edges), slices per edge — Швидкість бігунка (ребер за секунду), довжина хвоста (ребер), шматків на ребро. */
    static final float RUN_SPEED = 1.5f, RUN_TAIL = 1.6f;
    static final int RUN_SLICES = 6;

    /**
     * A bright light running along the sector frame, with a fading tail. Drawn a hair above the frame so it is not hidden by it.
     * Main thread only (shared temp vectors).
     * — Яскравий вогник, що біжить по рамці сектора, з хвостом, що згасає. Малюється трохи вище рамки, щоб вона його не ховала.
     * Лише головний потік (спільні тимчасові вектори).
     */
    static final Color runBase = new Color();

    static void drawRunner(PlanetRenderer planets, Sector sec, float alpha, Color color) {
        runBase.set(color);
        var corners = sec.tile.corners;
        int n = corners.length;
        runArad = (PlanetRenderer.outlineRad + 0.0005f) * sec.planet.radius;
        runCenter.set(sec.tile.v).scl(runArad);
        // globalTime keeps running while the game is paused on the planet map — globalTime іде далі, навіть коли гра на паузі на карті планети
        float head = (float) ((Time.globalTime / 60.0 * RUN_SPEED) % n);
        float tail = head - RUN_TAIL;
        // The lit stretch [tail, head] along the frame; if it wraps past corner 0, draw its end piece too
        // — Освітлений відрізок [tail, head] вздовж рамки; якщо він переходить через кут 0, малюємо і його кінець
        runSpan(planets, sec, corners, n, Math.max(tail, 0f), head, 0f, tail, alpha);
        if (tail < 0f) runSpan(planets, sec, corners, n, n + tail, n, n, tail, alpha);
        if (planets.batch.getNumVertices() >= planets.batch.getMaxVertices() - 6 * 6 * RUN_SLICES) planets.batch.flush(arc.graphics.Gl.triangles);
    }

    static float runArad;

    /** Draws frame positions [s, e] (in edges); off maps them back to the tail..head scale — Малює позиції рамки [s, e] (у ребрах); off переводить їх назад у шкалу tail..head. */
    static void runSpan(PlanetRenderer planets, Sector sec, PlanetGrid.Corner[] corners, int n, float s, float e, float off, float tail, float alpha) {
        if (e <= s) return;
        float stroke = 0.03f;
        for (int i = (int) Math.floor(s); i < Math.ceil(e) && i < n; i++) {
            float a = Math.max(s, i), b = Math.min(e, i + 1);
            if (b <= a) continue;
            runO1.set(corners[i].v).scl(runArad);
            runO2.set(corners[(i + 1) % n].v).scl(runArad);
            runI1.set(runO1).sub(runCenter).setLength(runO1.dst(runCenter) - stroke).add(runCenter);
            runI2.set(runO2).sub(runCenter).setLength(runO2.dst(runCenter) - stroke).add(runCenter);
            for (int k = 0; k < RUN_SLICES; k++) {
                float x0 = a + (b - a) * k / RUN_SLICES, x1 = a + (b - a) * (k + 1) / RUN_SLICES;
                runA0.set(runO1).lerp(runO2, x0 - i);
                runA1.set(runO1).lerp(runO2, x1 - i);
                runB0.set(runI1).lerp(runI2, x0 - i);
                runB1.set(runI1).lerp(runI2, x1 - i);
                // Brightness grows smoothly from the tail (0) to the head (1), per vertex — Яскравість плавно росте від хвоста (0) до голови (1), для кожної вершини
                Color c0 = runColor(runC0, (x0 - off - tail) / RUN_TAIL, alpha), c1 = runColor(runC1, (x1 - off - tail) / RUN_TAIL, alpha);
                var batch = planets.batch;
                batch.vertex(runA0, c0);
                batch.vertex(runA1, c1);
                batch.vertex(runB0, c0);
                batch.vertex(runB0, c0);
                batch.vertex(runA1, c1);
                batch.vertex(runB1, c1);
            }
        }
    }

    static final Color runC0 = new Color(), runC1 = new Color();

    static Color runColor(Color out, float f, float alpha) {
        f = Mathf.clamp(f);
        return out.set(runBase).lerp(Color.white, 0.6f).a(alpha * f * f * (3f - 2f * f));
    }

    /**
     * Frame color of a base: blue while someone plays there (and it is not under attack), orange otherwise.
     * — Колір рамки бази: синій, поки там хтось грає (і нема атаки), інакше помаранчевий.
     */
    public static Color frameColor(Sector sec, boolean attack, boolean waves) {
        // Smooth orange <-> blue; stays orange while the sector is still being captured — Плавно помаранчевий <-> синій; лишається помаранчевим, поки сектор ще захоплюють
        return frameTmp.set(baseColor).lerp(allyColor, weights(sec, true, waves, attack)[sec.id * 3]);
    }

}