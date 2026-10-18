package mindustry.ui.dialogs;

import arc.*;
import arc.scene.ui.*;
import arc.util.*;
import mindustry.*;
import arc.graphics.*;
import mindustry.gen.*;
import mindustry.ui.*;

/** AURORA: налаштування планети для хоста - відкрити/закрити для друзів, адреса, пароль, хто зараз грає. */
public class AuroraHostDialog extends BaseDialog{

    public AuroraHostDialog(){
        super("Aurora: планета");
        addCloseButton();
        shown(this::rebuild);
    }

    void rebuild(){
        cont.clear();
        cont.table(Styles.grayPanel, t -> {
            t.margin(12f).defaults().pad(5f).left();

            //стан
            t.add("Authority:");
            t.label(() -> !AuroraAuthority.running() ? "[scarlet]не запущений" :
                AuroraAuthority.scope() == AuroraAuthority.SCOPE_LOCAL ? "[accent]лише цей ПК" :
                AuroraAuthority.scope() == AuroraAuthority.SCOPE_TAILSCALE ? "[green]відкритий для Tailscale" :
                "[green]відкритий для LAN і Tailscale").row();

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

            t.image().color(Color.gray).height(2f).growX().colspan(2).pad(6f, 0f, 6f, 0f).row();

            //адреса
            String addr = AuroraNet.myIp() + ":" + AuroraNet.myPort();
            t.add("Адреса для друзів:");
            t.table(r -> {
                r.add("[accent]" + addr).padRight(8f);
                r.button(Icon.copySmall, Styles.clearNonei, () -> {
                    Core.app.setClipboardText(addr);
                    ui().showInfoFade("@copied");
                }).size(40f);
            }).row();
            t.add("[lightgray]Друзі: Грати > Приєднатись > ця адреса.").colspan(2).row();
            t.add("Порт Authority:");
            t.label(() -> AuroraAuthority.port() > 0 ? String.valueOf(AuroraAuthority.port()) : "-").row();

            //пароль
            t.add("Пароль (необов'язково):");
            TextField f = t.field(Core.settings.getString("aurora-token", ""), v -> Core.settings.put("aurora-token", v.trim())).width(260f).get();
            f.setMaxLength(40);
            t.row();
            t.add("[lightgray]Якщо задано, друзі мають ввести той самий пароль\n[lightgray]у своїх налаштуваннях Aurora.").colspan(2).row();

            t.image().color(Color.gray).height(2f).growX().colspan(2).pad(6f, 0f, 6f, 0f).row();

            //статистика
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
        }).width(Math.min(Core.graphics.getWidth() / arc.scene.ui.layout.Scl.scl(1f) - 40f, 560f));
    }

    static mindustry.core.UI ui(){
        return Vars.ui;
    }

}
