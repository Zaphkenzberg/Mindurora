package mindustry.ui.fragments;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.actions.*;
import arc.scene.event.*;
import arc.scene.style.*;
import arc.scene.ui.*;
import arc.scene.ui.ImageButton.*;
import arc.scene.ui.TextButton.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.core.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.type.*;
import mindustry.ctype.*;
import mindustry.AuroraNet;
import mindustry.AuroraAuthority;

import static mindustry.Vars.*;
import static mindustry.gen.Tex.*;

public class MenuFragment{
        private Table container, submenu;
        private Button currentMenu;
        private MenuRenderer renderer;
        private Seq<MenuButton> customButtons = new Seq<>();
        public Seq<MenuButton> desktopButtons = null;

        public void build(Group parent){
            AuroraNet.init();
            AuroraNet.checkAuthority(this::rebuildMenu);
            Timer.schedule(() -> {
                if(state.isMenu()) AuroraNet.watchAuthority(this::rebuildMenu);
            }, 10f, 10f);
            renderer = new MenuRenderer();

            Group group = new WidgetGroup();
            group.setFillParent(true);
            group.visible(() -> !ui.editor.isShown());
            parent.addChild(group);

            parent = group;

            mindustry.graphics.AuroraMenuRenderer auroraMenu = new mindustry.graphics.AuroraMenuRenderer();
            // AURORA: menu background is the Aurora planet; aurora-menu=false restores vanilla — Фон меню — планета Aurora; aurora-menu=false повертає ванільний
            parent.fill((x, y, w, h) -> {
                // Hidden under the fullscreen planet map / research tree: don't draw a second planet — Під картою планети / деревом досліджень: не малюємо другу планету
                if(ui.planet.isShown() || ui.research.isShown()) return;
                if(Core.settings.getBool("aurora-menu", true)) auroraMenu.render();
                else renderer.render();
            });

            parent.fill(c -> {
                c.pane(Styles.noBarPane, cont -> {
                    container = cont;
                    cont.name = "menu container";

                    if(!mobile){
                        c.left();
                        buildDesktop();
                        Events.on(ResizeEvent.class, event -> buildDesktop());
                    }else{
                        buildMobile();
                        Events.on(ResizeEvent.class, event -> buildMobile());
                    }
                }).with(pane -> {
                    pane.setOverscroll(false, false);
                }).grow();
            });

            parent.fill(c -> c.bottom().right().button(Icon.discord, new ImageButtonStyle(){{
                up = discordBanner;
            }}, ui.discord::show).marginTop(9f).marginLeft(10f).tooltip("@discord").size(84, 45).name("discord"));

            //info icon
            if(mobile){
                //left/right gutter areas
                parent.fill((x, y, w, h) -> {
                    x = 0f;
                    y = 0f;
                    w = Core.graphics.getWidth();
                    h = Core.graphics.getHeight();
                    if(Core.scene.marginLeft > 0){
                        paneRight.draw(x, y, Core.scene.marginLeft, h);
                    }

                    if(Core.scene.marginRight > 0){
                        paneLeft.draw(x + w - Core.scene.marginRight, y, Core.scene.marginRight, h);
                    }

                    if(Core.scene.marginBottom > 0){
                        Tex.paneTop.draw(Core.scene.marginLeft, 0, Core.graphics.getWidth() - Core.scene.marginRight - Core.scene.marginLeft, Core.scene.marginBottom);
                    }
                });

                parent.fill(c -> c.bottom().left().button("", new TextButtonStyle(){{
                    font = Fonts.def;
                    fontColor = Color.white;
                    up = infoBanner;
                }}, ui.about::show).size(84, 45).name("info"));
            }else if(becontrol.active()){
                parent.fill(c -> c.bottom().right().button("@be.check", Icon.refresh, () -> {
                    ui.loadfrag.show();
                    becontrol.checkUpdate(result -> {
                        ui.loadfrag.hide();
                        if(!result){
                            ui.showInfo("@be.noupdates");
                        }
                    });
                }).size(200, 60).name("becheck").update(t -> {
                    t.getLabel().setColor(becontrol.isUpdateAvailable() ? Tmp.c1.set(Color.white).lerp(Pal.accent, Mathf.absin(5f, 1f)) : Color.white);
                }));
            }

            String versionText = ((Version.build == -1) ? "[#fc8140aa]" : "[#ffffffba]") + Version.combined();
            parent.fill((x, y, w, h) -> {
                TextureRegion logo = Core.atlas.find("logo");
                float width = Core.graphics.getWidth(), height = Core.graphics.getHeight() - Core.scene.marginTop;
                float logoscl = Scl.scl(1) * logo.scale;
                float logow = Math.min(logo.width * logoscl, Core.graphics.getWidth() - Scl.scl(20));
                float logoh = logow * (float)logo.height / logo.width;

                float fx = (int)(width / 2f);
                float fy = (int)(height - 6 - logoh) + logoh / 2 - (Core.graphics.isPortrait() ? Scl.scl(30f) : 0f);
                if(Core.settings.getBool("macnotch") ){
                    fy -= Scl.scl(macNotchHeight);
                }

                Draw.color();
                Draw.rect(logo, fx, fy, logow, logoh);

                Fonts.outline.setColor(Color.white);
                Fonts.outline.draw(versionText, fx, fy - logoh/2f - Scl.scl(2f), Align.center);
            }).touchable = Touchable.disabled;
        }

        private void buildMobile(){
            container.clear();
            container.name = "buttons";
            container.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());

            // AURORA: buttons in one strip — bottom in landscape, left in portrait — Кнопки однією смугою: унизу горизонтально, зліва вертикально
            boolean land = !Core.graphics.isPortrait();

            MobileButton
                play = new MobileButton(Icon.play, "@campaign", () -> {switchCampaign(0); checkPlay(ui.planet::show);}),
                    multcamp = new MobileButton(AuroraNet.authorityOk ? Icon.planet : Icon.warning, "Aurora", this::openAurora),
                custom = new MobileButton(Icon.rightOpenOut, "@customgame", normal(() -> checkPlay(ui.custom::show))),
                maps = new MobileButton(Icon.download, "@loadgame", normal(() -> checkPlay(ui.load::show))),
                join = new MobileButton(Icon.add, "@joingame", () -> checkPlay(ui.join::show)),
                editor = new MobileButton(Icon.terrain, "@editor", normal(() -> checkPlay(ui.maps::show))),
                tools = new MobileButton(Icon.settings, "@settings", ui.settings::show),
                mods = new MobileButton(Icon.book, "@mods", ui.mods::show),
                exit = new MobileButton(Icon.exit, "@quit", () -> Core.app.exit()),
                about = new MobileButton(Icon.info, "@about.button", ui.about::show);

            Seq<MobileButton> customs = customButtons.map(b -> new MobileButton(b.icon, b.text, b.runnable == null ? () -> {} : b.runnable));

            Seq<MobileButton> all = Seq.with(play, multcamp, join, custom, maps, editor, tools, mods);
            all.addAll(customs);
            all.add((MobileButton)(ios ? about : exit));

            // Button size from the button count and free space (UI units), 56..88 — Розмір кнопки під кількість і вільне місце (одиниці UI), 56..88
            float pad = 3f;
            float avail = land
                ? (Core.graphics.getWidth() - Core.scene.marginLeft - Core.scene.marginRight) / Scl.scl(1f) - 120f
                : (Core.graphics.getHeight() - Core.scene.marginTop - Core.scene.marginBottom) / Scl.scl(1f) - 180f; // minus the logo — мінус логотип
            float size = Mathf.clamp(avail / all.size - pad * 2f, 56f, 88f);
            container.margin(0f);

            // No scrolling (sizes fit the screen), so the table fills the screen and alignment works:
            // centered strip at the bottom / centered column on the left
            // — Без прокрутки (розмір під екран), тож таблиця на весь екран і вирівнювання працює:
            // смуга внизу по центру / стовпчик зліва по центру
            if(container.parent instanceof arc.scene.ui.ScrollPane sp) sp.setScrollingDisabled(true, true);

            Table strip = new Table();
            strip.defaults().size(size).pad(pad);
            if(land){
                for(var b : all) strip.add(b);
                container.add(strip).expand().bottom().padBottom(10f);
            }else{
                for(var b : all) strip.add(b).row();
                container.add(strip).expand().left().padLeft(6f);
            }
        }

        private void buildDesktop(){
            container.clear();
            container.setSize(Core.graphics.getWidth(), Core.graphics.getHeight());

            float width = 230f;
            Drawable background = Styles.black6;

            container.left();
            container.add().width(Core.graphics.getWidth()/10f);
            container.table(background, t -> {
                t.defaults().width(width).height(70f);
                t.name = "buttons";

                if(desktopButtons == null){
                    desktopButtons = Seq.with(
                        new MenuButton("@play", Icon.play,
                            new MenuButton("@campaign", Icon.play, () -> {switchCampaign(0); checkPlay(ui.planet::show);}),
                                new MenuButton("Aurora", AuroraNet.authorityOk ? Icon.planet : Icon.warning, this::openAurora),
                            new MenuButton("@joingame", Icon.add, () -> checkPlay(ui.join::show)),
                            new MenuButton("@customgame", Icon.terrain, normal(() -> checkPlay(ui.custom::show))),
                            new MenuButton("@loadgame", Icon.download, normal(() -> checkPlay(ui.load::show)))
                        ),
                        new MenuButton("@database.button", Icon.menu,
                            new MenuButton("@schematics", Icon.paste, ui.schematics::show),
                            new MenuButton("@database", Icon.book, ui.database::show),
                            new MenuButton("@about.button", Icon.info, ui.about::show)
                        ),
                        new MenuButton("@editor", Icon.terrain, normal(() -> checkPlay(ui.maps::show))), steam ? new MenuButton("@workshop", Icon.steam, platform::openWorkshop) : null,
                        new MenuButton("@mods", Icon.book, ui.mods::show),
                        new MenuButton("@settings", Icon.settings, ui.settings::show)
                    );
                }

                buttons(t, desktopButtons.toArray(MenuButton.class));
                buttons(t, customButtons.toArray(MenuButton.class));
                buttons(t, new MenuButton("@quit", Icon.exit, Core.app::exit));
            }).width(width).growY();

            container.table(background, t -> {
                submenu = t;
                t.name = "submenu";
                t.color.a = 0f;
                t.top();
                t.defaults().width(width).height(70f);
                t.visible(() -> !t.getChildren().isEmpty());

            }).width(width).growY();
        }

        private void checkPlay(Runnable run){
            if(!mods.hasContentErrors()){
                run.run();
            }else{
                ui.showInfo("@mod.noerrorplay");
            }
        }
    private void rebuildMenu(){
        if(mobile){
            buildMobile();
        }else{
            desktopButtons = null;
            buildDesktop();
        }
    }
    /** AURORA: own planet. Our Authority starts at once; if the port is busy, the settings dialog — Своя планета. Свій Authority стартує одразу; якщо порт зайнятий — вікно налаштувань. */
    void openAurora(){
        if(!AuroraAuthority.start()){
            ui.showInfoToast(mindustry.AuroraNet.tr("authority.startfail", String.valueOf(Core.settings.getInt("aurora-port", 8443))), 5f);
            new mindustry.ui.dialogs.AuroraSettingsDialog(() -> AuroraNet.checkAuthority(this::rebuildMenu)).show();
            return;
        }
        AuroraNet.authorityOk = true;
        switchCampaign(1);
        checkPlay(ui.planet::show);
    }

    /** AURORA: normal modes (custom game, load, editor) always use the normal slot, never Aurora saves — Звичайні режими (своя гра, завантаження, редактор) завжди у звичайному слоті, не в сейвах Aurora. */
    Runnable normal(Runnable r){
        return () -> {
            if(campaignSlot != 0) switchCampaign(0);
            r.run();
        };
    }

    /** AURORA: switch campaign slot: save folder, keys, unlocks, research progress — Перемкнути слот кампанії: папка сейвів, ключі, дослідження, прогрес. */
    public void switchCampaign(int slot) {
            // Same slot: everything is already loaded (guest is the exception, it is wiped before each join) — Той самий слот: усе вже завантажено (виняток — гість, його стирають перед кожним входом)
            if(slot == campaignSlot && slot != slotGuest && saveDirectory != null && saveDirectory.equals(dataDirectory.child(campaignSaveDir(slot)))) return;
            campaignSlot = slot;
            saveDirectory = dataDirectory.child(campaignSaveDir(slot));
            universe.reloadForSlot();

            for (Planet p : content.planets()) {
                for (Sector s : p.sectors) s.save = null;
            }

            control.saves.load();

            for (Planet p : content.planets()) {
                for (Sector s : p.sectors) s.loadInfo();
                p.loadRules(); // own difficulty per slot — своя складність для кожного слоту
            }
                content.each(c -> {
                    if (c instanceof UnlockableContent u) u.reloadUnlock();
                });
            if(slot != 0) AuroraNet.syncUnlocks();
            for(var node : mindustry.content.TechTree.all) node.reloadProgress();
            AuroraNet.onCampaignSlot(slot);
        }
        private void fadeInMenu(){
            submenu.clearActions();
            submenu.actions(Actions.alpha(1f, 0.15f, Interp.fade));
        }

        private void fadeOutMenu(){
            //nothing to fade out
            if(submenu.getChildren().isEmpty()){
                return;
            }

            submenu.clearActions();
            submenu.actions(Actions.alpha(1f), Actions.alpha(0f, 0.2f, Interp.fade), Actions.run(() -> submenu.clearChildren()));
        }

        private void buttons(Table t, MenuButton... buttons){
            for(MenuButton b : buttons){
                if(b == null) continue;
                Button[] out = {null};
                out[0] = t.button(b.text, b.icon, Styles.flatToggleMenut, () -> {
                    if(currentMenu == out[0]){
                        currentMenu = null;
                        fadeOutMenu();
                    }else{
                        if(b.submenu != null && b.submenu.any()){
                            currentMenu = out[0];
                            submenu.clearChildren();
                            fadeInMenu();
                            //correctly offset the button
                            submenu.add().height((Core.graphics.getHeight() - Core.scene.marginTop - Core.scene.marginBottom - out[0].getY(Align.topLeft)) / Scl.scl(1f));
                            submenu.row();
                            buttons(submenu, b.submenu.toArray());
                        }else{
                            currentMenu = null;
                            fadeOutMenu();
                            b.runnable.run();
                        }
                    }
                }).marginLeft(11f).get();
                out[0].update(() -> out[0].setChecked(currentMenu == out[0]));
                t.row();
            }
        }

        /** Adds a custom button to the menu. */
        public void addButton(String text, Drawable icon, Runnable callback){
            addButton(new MenuButton(text, icon, callback));
        }

        /** Adds a custom button to the menu. */
        public void addButton(String text, Runnable callback){
            addButton(text, Styles.none, callback);
        }

        /**
         * Adds a custom button to the menu.
         * If {@link MenuButton#submenu} is null or the player is on mobile, {@link MenuButton#runnable} is invoked on click.
         * Otherwise, {@link MenuButton#submenu} is shown.
         */
        public void addButton(MenuButton button){
            customButtons.add(button);
        }

        /** Represents a menu button definition. */
        public static class MenuButton{
            public final Drawable icon;
            public final String text;
            /** Runnable ran when the button is clicked. Ignored on desktop if {@link #submenu} is not null. */
            public final Runnable runnable;
            /** Submenu shown when this button is clicked. Used instead of {@link #runnable} on desktop. */
            public final @Nullable Seq<MenuButton> submenu;

            /** Constructs a simple menu button, which behaves the same way on desktop and mobile. */
            public MenuButton(String text, Drawable icon, Runnable runnable){
                this.icon = icon;
                this.text = text;
                this.runnable = runnable;
                this.submenu = null;
            }

            /** Constructs a button that runs the runnable when clicked on mobile or shows the submenu on desktop. */
            public MenuButton(String text, Drawable icon, Runnable runnable, MenuButton... submenu){
                this.icon = icon;
                this.text = text;
                this.runnable = runnable;
                this.submenu = submenu != null ? Seq.with(submenu) : null;
            }

            /** Comstructs a desktop-only button; used internally. */
            MenuButton(String text, Drawable icon, MenuButton... submenu){
                this.icon = icon;
                this.text = text;
                this.runnable = () -> {};
                this.submenu = submenu != null ? Seq.with(submenu) : null;
            }
        }
}
