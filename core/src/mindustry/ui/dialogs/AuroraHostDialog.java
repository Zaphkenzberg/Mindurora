package mindustry.ui.dialogs;

import arc.*;
import arc.scene.ui.*;
import arc.graphics.*;
import arc.scene.ui.layout.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.ui.*;

/**
 * Planet settings for the planet host: who may join, address for friends, password, who is playing now.
 * — Налаштування планети для хоста планети: хто може зайти, адреса для друзів, пароль, хто зараз грає.
 */
public class AuroraHostDialog extends BaseDialog{

    public AuroraHostDialog(){
        super("Aurora: планета");
        addCloseButton();
        shown(this::rebuild);
    }

    static void separator(Table t){
        t.image().color(Color.gray).height(2f).growX().colspan(2).pad(6f, 0f, 6f, 0f).row();
    }

    void rebuild(){
        cont.clear();
        cont.table(Styles.grayPanel, t -> {
            t.margin(12f).defaults().pad(5f).left();

            // Status — Стан
            t.add("Authority:");
            t.label(() -> !AuroraAuthority.running() ? "[scarlet]не запущений" :
                AuroraAuthority.scope() == AuroraAuthority.SCOPE_LOCAL ? "[accent]лише цей ПК" :
                AuroraAuthority.scope() == AuroraAuthority.SCOPE_TAILSCALE ? "[green]відкритий для Tailscale" :
                "[green]відкритий для LAN і Tailscale").row();

            // Access scope — Хто може зайти
            t.add("Хто може зайти:");
            t.table(r -> {
                String[] names = {"Лише я", "LAN + Tailscale", "Лише Tailscale"};
                ButtonGroup<TextButton> group = new ButtonGroup<>();
                for(int i = 0; i < names.length; i++){
                    int s = i;
                    r.button(names[i], Styles.flatTogglet, () -> {
                        AuroraAuthority.setScope(s);
                        if(s != AuroraAuthority.SCOPE_LOCAL && !AuroraAuthority.running()) AuroraAuthority.start();
                    }).group(group).checked(b -> AuroraAuthority.scope() == s).size(150f, 44f).pad(2f);
                }
            }).row();
            t.add("[lightgray]З інтернету (публічні IP) Authority не пускає ніколи,\n[lightgray]навіть якщо порт 8443 відкритий на роутері.").colspan(2).row();

            separator(t);

            // Address for friends — Адреса для друзів
            String addr = AuroraNet.myIp() + ":" + AuroraNet.myPort();
            t.add("Адреса для друзів:");
            t.table(r -> {
                r.add("[accent]" + addr).padRight(8f);
                r.button(Icon.copySmall, Styles.clearNonei, () -> {
                    Core.app.setClipboardText(addr);
                    Vars.ui.showInfoFade("@copied");
                }).size(40f);
            }).row();
            t.add("[lightgray]Друзі: Грати > Приєднатись > ця адреса.").colspan(2).row();
            t.add("Порт Authority:");
            t.label(() -> AuroraAuthority.port() > 0 ? String.valueOf(AuroraAuthority.port()) : "-").row();

            // Password (X-Aurora-Token) — Пароль (X-Aurora-Token)
            t.add("Пароль (необов'язково):");
            TextField f = t.field(Core.settings.getString("aurora-token", ""), v -> Core.settings.put("aurora-token", v.trim())).width(260f).get();
            f.setMaxLength(40);
            t.row();
            t.add("[lightgray]Якщо задано, друзі мають ввести той самий пароль\n[lightgray]у своїх налаштуваннях Aurora.").colspan(2).row();

            separator(t);

            // Live stats — Жива статистика
            t.add("Зараз грають у секторах:");
            t.label(() -> String.valueOf(AuroraAuthority.liveCount())).row();
            t.label(() -> {
                var list = AuroraAuthority.liveList();
                return list.isEmpty() ? "[lightgray]нікого" : "[lightgray]" + String.join("\n", list);
            }).colspan(2).left().row();
            t.add("Збережених секторів:");
            t.label(() -> String.valueOf(AuroraAuthority.savedCount())).row();
            t.add("Спільних досліджень:");
            t.label(() -> String.valueOf(AuroraAuthority.unlockCount())).row();
        }).width(Math.min(Core.graphics.getWidth() / Scl.scl(1f) - 40f, 560f));
    }
}
