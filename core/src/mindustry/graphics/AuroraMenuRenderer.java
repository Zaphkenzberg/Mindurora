package mindustry.graphics;

import arc.*;
import arc.files.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.gl.*;
import arc.math.*;
import arc.graphics.g3d.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.graphics.g3d.*;
import mindustry.type.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

/**
 * AURORA main menu background: a rotating planet showing the Aurora campaign state, a glitchy shield and a 3D cage
 * anchored to the planet. Drag to rotate, mouse wheel to zoom.
 * — Фон головного меню AURORA: планета зі станом кампанії Aurora, щит з перешкодами і 3D-клітка,
 * прив'язана до планети. Тягни, щоб крутити, колесо — зум.
 */
public class AuroraMenuRenderer implements PlanetRenderer.PlanetInterfaceRenderer{
    final PlanetParams params = new PlanetParams();
    final FrameBuffer buffer = new FrameBuffer(2, 2);
    final Vec3 tmp = new Vec3(), tmp2 = new Vec3();
    final Color shield = mindustry.AuroraNet.shieldColor;
    float radius = 100f;

    // Camera control: yaw/pitch in degrees, inertia after release — Керування камерою: yaw/pitch у градусах, інерція після відпускання
    float yaw, pitch = 19f, velYaw, velPitch;
    boolean dragging;
    float zoomOffset;
    static final float autoSpin = 0.08f;
    /** Drag sensitivity: weaker on phones (touch deltas are big in pixels) — Чутливість перетягування: на телефоні слабша (дельти дотику великі в пікселях). */
    static final float dragSensDesktop = 0.25f, dragSensMobile = 0.05f;

    // 3D cage: the same hex/pentagon grid as sectors (Goldberg polyhedron) — 3D-клітка: та сама сітка, що й сектори (Goldberg-поліедр)
    static final int cageSize = 1;          // 0 dodecahedron, 1 football (12 pent + 20 hex), 2 denser — 0 додекаедр, 1 м'яч (12 п'ятикутників + 20 шестикутників), 2 густіше
    static final float cageScale = 1.55f;   // cage height in planet radii — висота клітки в радіусах планети
    final PlanetGrid grid = PlanetGrid.create(cageSize);
    final Mat3D mat = new Mat3D();
    final Vec3 camPosWorld = new Vec3();
    // Screen coords of cage corners (v), anchors on the surface (s) and pentagon centers on the cage (a)
    // — Екранні координати вершин клітки (v), точок прив'язки на поверхні (s) і центрів п'ятикутників на клітці (a)
    final float[] vx, vy, sx, sy, ax, ay;
    final boolean[] vFront, sFront;
    final int[] anchors;

    // Real occlusion: edges and tethers are split into samples, each tested by a ray from the camera
    // — Справжнє перекриття: ребра й троси розбиті на точки, кожна перевіряється променем від камери
    static final int edgeSteps = 12, tetherSteps = 8;
    final Vec3[] cornerWorld;
    final float[] ex, ey, tx, ty;
    final boolean[] eVis, tVis, vVis;
    final Vec3 tmp3 = new Vec3();
    float occR;

    public AuroraMenuRenderer(){
        params.planet = Planets.serpulo;
        // drawUi makes the planet call renderSectors (Aurora sector state); orbits are hidden during render
        // — drawUi потрібен, щоб планета викликала renderSectors (стан секторів Aurora); орбіти ховаємо під час рендеру
        params.drawUi = true;
        params.uiAlpha = 1f;
        params.mouseGrid = false;
        params.renderer = this;
        params.drawSkybox = true;
        params.alwaysDrawAtmosphere = true;
        params.zoom = 1.6f;

        int nc = grid.corners.length;
        vx = new float[nc];
        vy = new float[nc];
        vFront = new boolean[nc];
        IntSeq pent = new IntSeq();
        for(PlanetGrid.Ptile t : grid.tiles) if(t.edgeCount == 5) pent.add(t.id);
        anchors = pent.toArray();
        sx = new float[anchors.length];
        sy = new float[anchors.length];
        ax = new float[anchors.length];
        ay = new float[anchors.length];
        sFront = new boolean[anchors.length];

        cornerWorld = new Vec3[nc];
        for(int i = 0; i < nc; i++) cornerWorld[i] = new Vec3();
        int ne = grid.edges.length;
        ex = new float[ne * edgeSteps];
        ey = new float[ne * edgeSteps];
        eVis = new boolean[ne * edgeSteps];
        vVis = new boolean[nc];
        tx = new float[anchors.length * tetherSteps];
        ty = new float[anchors.length * tetherSteps];
        tVis = new boolean[anchors.length * tetherSteps];

        int ns = params.planet.sectors.size;
        captured = new boolean[ns];
        attacked = new boolean[ns];
        infos = new SectorInfo[ns];
    }

    // ---------- Aurora campaign state (own planet: saves2/ and c2- keys) — Стан кампанії Aurora (своя планета: saves2/ і ключі c2-) ----------

    boolean[] captured, attacked, orbits;
    SectorInfo[] infos;
    int lastCap = -1, lastAtt = -1;
    float scanTimer = 9999f;

    /** Reads own Aurora saves and SectorInfo directly, without switching the current slot — Читає сейви й SectorInfo своєї Aurora напряму, не перемикаючи слот. */
    void scan(){
        Fi dir = dataDirectory.child(campaignSaveDir(slotAurora));
        String pn = params.planet.name;
        for(Sector sec : params.planet.sectors){
            boolean save = dir.child("sector-" + pn + "-" + sec.id + "." + saveExtension).exists();
            boolean cap = false, att = false;
            SectorInfo info = null;
            if(save){
                info = Core.settings.getJson("c2-" + pn + "-s-" + sec.id + "-info", SectorInfo.class, SectorInfo::new);
                cap = info.hasCore;
                att = cap && (info.waves || info.attack);
            }
            captured[sec.id] = cap;
            attacked[sec.id] = att;
            infos[sec.id] = info;
        }

        // Log only when something changed — Пишемо в лог лише при змінах
        int nc = 0, na = 0;
        for(int i = 0; i < captured.length; i++){
            if(captured[i]) nc++;
            if(attacked[i]) na++;
        }
        if(nc != lastCap || na != lastAtt){
            lastCap = nc;
            lastAtt = na;
            Log.info("[Aurora menu] @: captured=@, attacked=@ (dir=@)", pn, nc, na, dir.absolutePath());
        }
    }

    @Override
    public void renderSectors(Planet planet){
        if(planet != params.planet) return;
        var planets = renderer.planets;

        for(Sector sec : planet.sectors){
            // Everyone's bases in the normal color (Sharded) — Бази всіх гравців звичайним кольором (Sharded)
            boolean lb = captured[sec.id], rb = mindustry.AuroraNet.remoteBase(sec);
            if(lb || rb){
                planets.drawSelection(sec, Tmp.c1.set(mindustry.AuroraNet.baseColor).mul(0.8f).a(1f), 0.026f, -0.001f);
            }

            // Live sectors: blue frame / blinking blue / blinking red — Живі сектори: синя рамка / блимання синім / блимання червоним
            SectorInfo info = infos[sec.id];
            mindustry.AuroraNet.drawLiveSector(planets, sec, lb || rb,
                lb && info != null ? info.waves : mindustry.AuroraNet.remoteWaves(sec),
                lb && info != null ? info.attack : mindustry.AuroraNet.remoteAttack(sec), 1f);
        }

        planets.batch.flush(Gl.triangles);
    }

    @Override
    public void renderProjections(Planet planet){
        if(planet != params.planet) return;
        var planets = renderer.planets;
        float iw = 64f / 4f;

        // Icons like on the campaign map: ⚠ for attack, else the sector icon — Іконки як на карті кампанії: ⚠ для нападу, інакше іконка сектора
        for(Sector sec : planet.sectors){
            SectorInfo info = infos[sec.id];
            if(info == null || !captured[sec.id]) continue;

            TextureRegion icon = attacked[sec.id] ? Fonts.getLargeIcon("warning") :
                info.contentIcon != null ? info.contentIcon.uiIcon :
                info.icon != null ? Fonts.getLargeIcon(info.icon) : null;
            if(icon == null) continue;

            Color color = attacked[sec.id] ? mindustry.AuroraNet.baseColor : Color.white;
            planets.drawPlane(sec, () -> {
                Draw.color(color);
                Draw.rect(icon, 0, 0, iw, iw * icon.height / icon.width);
            });
        }
        Draw.reset();
    }

    public void render(){
        int w = Core.graphics.getWidth(), h = Core.graphics.getHeight();
        updateInput();

        // Camera on a sphere around the planet — Камера на сфері навколо планети
        params.camPos.set(0f, 0f, 1f).rotate(Vec3.X, -pitch).rotate(Vec3.Y, yaw);
        // Further away in portrait than in landscape; the wheel adds an offset — У вертикальному режимі далі, ніж у горизонтальному; колесо додає зсув
        params.zoom = (Core.graphics.isPortrait() ? 1.9f : 1.6f) + zoomOffset;
        params.viewW = w;
        params.viewH = h;

        // Re-read Aurora sector state every 5 s — Раз на 5 с перечитуємо стан секторів Aurora
        scanTimer += Time.delta;
        if(scanTimer > 60f * 5f){
            scanTimer = 0f;
            try{
                scan();
            }catch(Throwable e){
                Log.err("Aurora menu scan failed", e);
            }
        }

        // No orbit rings on the menu background (restored after render) — Орбіти на фоні меню не потрібні (відновлюємо після рендеру)
        var all = content.planets();
        if(orbits == null || orbits.length != all.size) orbits = new boolean[all.size];
        for(int i = 0; i < all.size; i++){
            orbits[i] = all.get(i).drawOrbit;
            all.get(i).drawOrbit = false;
        }

        Draw.flush();
        buffer.resize(w, h);
        buffer.begin(Color.black);
        renderer.planets.render(params);

        // Planet radius on screen: center and a point on the edge — Радіус планети на екрані: центр і точка на краю
        Camera3D cam = renderer.planets.cam;
        float pr = params.planet.radius;
        tmp.set(params.planet.position);
        tmp2.set(cam.up).nor().scl(pr).add(params.planet.position);
        cam.project(tmp);
        cam.project(tmp2);
        float r = tmp.dst(tmp2);
        if(r > 1f && !Float.isNaN(r)) radius = r;
        projectCage(cam, pr);
        buffer.end();
        for(int i = 0; i < all.size; i++) all.get(i).drawOrbit = orbits[i];

        Draw.color();
        Draw.rect(Draw.wrap(buffer.getTexture()), w / 2f, h / 2f, w, -h);

        drawShield(w / 2f, h / 2f, radius);
        Draw.reset();
    }

    /** Projects cage corners, edge samples and tethers to the screen. Call while the planet camera is active
     * — Проєктує вершини клітки, точки ребер і троси на екран. Викликати, поки камера планети активна. */
    void projectCage(Camera3D cam, float pr){
        Vec3 center = params.planet.position;
        params.planet.getTransform(mat);
        camPosWorld.set(cam.position);
        occR = pr * 0.99f;

        for(PlanetGrid.Corner c : grid.corners){
            Mat3D.prj(cornerWorld[c.id].set(c.v).nor().scl(pr * cageScale), mat);
            tmp2.set(cornerWorld[c.id]);
            vFront[c.id] = front(tmp2, center);
            vVis[c.id] = !occluded(tmp2, center);
            cam.project(tmp2);
            vx[c.id] = tmp2.x;
            vy[c.id] = tmp2.y;
        }

        // Edges: samples along the line between corners — Ребра: точки вздовж прямої між вершинами
        for(PlanetGrid.Edge e : grid.edges){
            Vec3 va = cornerWorld[e.corners[0].id], vb = cornerWorld[e.corners[1].id];
            for(int k = 0; k < edgeSteps; k++){
                int idx = e.id * edgeSteps + k;
                tmp2.set(va).lerp(vb, k / (float)(edgeSteps - 1));
                eVis[idx] = !occluded(tmp2, center);
                cam.project(tmp2);
                ex[idx] = tmp2.x;
                ey[idx] = tmp2.y;
            }
        }

        for(int i = 0; i < anchors.length; i++){
            Vec3 dir = grid.tiles[anchors[i]].v;
            // Anchor on the surface — Точка прив'язки на поверхні
            Mat3D.prj(tmp3.set(dir).nor().scl(pr * 1.01f), mat);
            tmp2.set(tmp3);
            sFront[i] = front(tmp2, center);
            cam.project(tmp2);
            sx[i] = tmp2.x;
            sy[i] = tmp2.y;
            // Pentagon center on the cage — Центр п'ятикутника на клітці
            Mat3D.prj(tmp.set(dir).nor().scl(pr * cageScale), mat);
            // Tether from the cage to the surface — Трос від клітки до поверхні
            for(int k = 0; k < tetherSteps; k++){
                int idx = i * tetherSteps + k;
                tmp2.set(tmp).lerp(tmp3, k / (float)(tetherSteps - 1));
                tVis[idx] = !occluded(tmp2, center);
                cam.project(tmp2);
                tx[idx] = tmp2.x;
                ty[idx] = tmp2.y;
            }
            tmp2.set(tmp);
            cam.project(tmp2);
            ax[i] = tmp2.x;
            ay[i] = tmp2.y;
        }
    }

    /** Is the point hidden by the planet: the camera ray hits the planet sphere first — Чи точку закриває планета: промінь від камери раніше влучає в кулю планети. */
    boolean occluded(Vec3 p, Vec3 center){
        float dx = p.x - camPosWorld.x, dy = p.y - camPosWorld.y, dz = p.z - camPosWorld.z;
        float len = (float)Math.sqrt(dx * dx + dy * dy + dz * dz);
        if(len < 0.0001f) return false;
        dx /= len; dy /= len; dz /= len;
        float ox = center.x - camPosWorld.x, oy = center.y - camPosWorld.y, oz = center.z - camPosWorld.z;
        float tca = ox * dx + oy * dy + oz * dz;
        if(tca < 0f) return false;
        float d2 = ox * ox + oy * oy + oz * oz - tca * tca;
        float r2 = occR * occR;
        if(d2 > r2) return false;
        float t0 = tca - (float)Math.sqrt(r2 - d2);
        return t0 > 0f && t0 < len;
    }

    /** Is the point on the camera-facing half of the cage — Чи точка на повернутій до камери половині клітки. */
    boolean front(Vec3 p, Vec3 center){
        float nx = p.x - center.x, ny = p.y - center.y, nz = p.z - center.z;
        float dx = camPosWorld.x - p.x, dy = camPosWorld.y - p.y, dz = camPosWorld.z - p.z;
        return nx * dx + ny * dy + nz * dz > 0f;
    }

    void drawCage(float jx, float jy, float alpha, Color col, float t){
        // Back half thin and faint, front bright; what the planet hides is skipped — Задня половина тонка й тьмяна, передня яскрава; закрите планетою не малюється
        for(int pass = 0; pass < 2; pass++){
            boolean wantFront = pass == 1;
            Lines.stroke(Scl.scl(wantFront ? 3.2f : 1.2f));
            Draw.color(col, alpha * (wantFront ? 1f : 0.22f));
            for(PlanetGrid.Edge e : grid.edges){
                boolean fr = vFront[e.corners[0].id] && vFront[e.corners[1].id];
                if(fr != wantFront) continue;
                int base = e.id * edgeSteps;
                for(int k = 0; k < edgeSteps - 1; k++){
                    if(!eVis[base + k] || !eVis[base + k + 1]) continue;
                    Lines.line(ex[base + k] + jx, ey[base + k] + jy, ex[base + k + 1] + jx, ey[base + k + 1] + jy);
                }
            }
        }

        // Nodes: front normal, back small and faint, hidden ones skipped — Вузли: передні звичайні, задні малі й тьмяні, закриті — ні
        for(int i = 0; i < vx.length; i++){
            if(!vVis[i]) continue;
            Draw.color(col, alpha * (vFront[i] ? 1f : 0.25f));
            Fill.circle(vx[i] + jx, vy[i] + jy, Scl.scl(vFront[i] ? 2.6f : 1.4f));
        }

        // Dashed tethers from pentagon centers to the anchors — Пунктирні троси від центрів п'ятикутників до точок прив'язки
        for(int i = 0; i < anchors.length; i++){
            float a = alpha * (sFront[i] ? 0.8f : 0.2f);
            Lines.stroke(Scl.scl(1.2f));
            Draw.color(col, a * 0.6f);
            int base = i * tetherSteps;
            for(int k = 0; k < tetherSteps - 1; k += 2){
                if(!tVis[base + k] || !tVis[base + k + 1]) continue;
                Lines.line(tx[base + k] + jx, ty[base + k] + jy, tx[base + k + 1] + jx, ty[base + k + 1] + jy);
            }
            if(sFront[i]){
                Draw.color(col, a);
                Fill.square(sx[i] + jx, sy[i] + jy, Scl.scl(3f + Mathf.absin(t + i * 10f, 5f, 1.5f)), 45f);
            }
        }
    }

    /** Drag rotates, release keeps spinning with inertia, wheel zooms — Тягнеш — крутиш, відпустив — докручує з інерцією, колесо — зум. */
    void updateInput(){
        if(Core.input.justTouched()) dragging = !overUi();
        if(!Core.input.isTouched()) dragging = false;

        if(dragging){
            float dragSens = mobile ? dragSensMobile : dragSensDesktop;
            velYaw = -Core.input.deltaX() * dragSens;
            velPitch = -Core.input.deltaY() * dragSens;
            yaw += velYaw;
            pitch += velPitch;
        }else{
            // Inertia fades, then the usual auto-spin — Інерція згасає, далі звичайне автообертання
            velYaw = Mathf.lerpDelta(velYaw, 0f, 0.06f);
            velPitch = Mathf.lerpDelta(velPitch, 0f, 0.06f);
            yaw += velYaw * Time.delta + autoSpin * Time.delta;
            pitch += velPitch * Time.delta;
        }
        pitch = Mathf.clamp(pitch, -80f, 80f);

        if(!overUi()){
            float scroll = Core.input.axis(arc.input.KeyCode.scroll);
            if(scroll != 0f) zoomOffset = Mathf.clamp(zoomOffset - scroll * 0.08f, -0.8f, 0.8f);
        }
    }

    /** Is the pointer over a button, text field or dialog (then don't rotate) — Чи курсор над кнопкою, полем чи вікном (тоді не крутимо). */
    boolean overUi(){
        arc.scene.Element e = Core.scene.hit(Core.input.mouseX(), Core.input.mouseY(), true);
        while(e != null){
            if(e instanceof arc.scene.ui.Button || e instanceof arc.scene.ui.TextField || e instanceof arc.scene.ui.Dialog) return true;
            e = e.parent;
        }
        return false;
    }

    void drawShield(float cx, float cy, float r){
        float t = Time.time;
        int seed = (int)(t / 5f);
        // Rare strong glitch: the whole shield jerks — Рідкісний сильний збій: увесь щит смикається
        boolean glitch = Mathf.randomSeed(seed * 7L) < 0.07f;
        float jx = glitch ? Mathf.randomSeedRange(seed * 13L, 10f) : 0f;
        float jy = glitch ? Mathf.randomSeedRange(seed * 17L, 4f) : 0f;
        float sr = r * 1.18f;

        // Faint glow inside — Слабке світіння всередині
        Draw.color(shield, 0.06f + Mathf.absin(t, 20f, 0.04f));
        Fill.circle(cx + jx, cy + jy, sr);

        // Ring of segments, some missing and flickering — Кільце з сегментів, деякі пропадають і мерехтять
        int segs = 90;
        Lines.stroke(Scl.scl(2.2f));
        for(int i = 0; i < segs; i++){
            float roll = Mathf.randomSeed(seed * 131L + i);
            if(roll < 0.08f) continue;
            float a = 0.35f + 0.5f * Mathf.randomSeed(seed * 31L + i * 3L) + Mathf.absin(t + i * 4f, 6f, 0.15f);
            Draw.color(shield, Mathf.clamp(a));
            float a1 = 360f / segs * i, a2 = a1 + 360f / segs * 0.8f;
            Lines.line(cx + jx + Angles.trnsx(a1, sr), cy + jy + Angles.trnsy(a1, sr),
                cx + jx + Angles.trnsx(a2, sr), cy + jy + Angles.trnsy(a2, sr));
        }

        // Interference: short horizontal stripes inside the shield — Перешкоди: короткі горизонтальні смуги в зоні щита
        int lines = glitch ? 60 : 24;
        for(int i = 0; i < lines; i++){
            float ly = cy + Mathf.randomSeedRange(seed * 977L + i, sr);
            float half = Mathf.sqrt(Math.max(sr * sr - (ly - cy) * (ly - cy), 0f));
            float lx = cx + Mathf.randomSeedRange(seed * 557L + i, half);
            float len = Mathf.randomSeed(seed * 331L + i, 6f, 40f) * Scl.scl(1f);
            Draw.color(Color.white, Mathf.randomSeed(seed * 71L + i, 0.05f, glitch ? 0.4f : 0.18f));
            Lines.stroke(Scl.scl(Mathf.randomSeed(seed * 19L + i, 1f, 2f)));
            Lines.line(lx - len / 2f, ly, lx + len / 2f, ly);
        }

        // 3D cage anchored to the planet; RGB split while glitching — 3D-клітка, прив'язана до планети; RGB-зсув під час збою
        if(glitch){
            drawCage(jx + Scl.scl(4f), jy, 0.4f, Color.scarlet, t);
            drawCage(jx - Scl.scl(4f), jy, 0.4f, Color.cyan, t);
        }
        drawCage(jx, jy, 0.65f + Mathf.absin(t, 9f, 0.3f), mindustry.AuroraNet.cageColor, t);
    }
}
