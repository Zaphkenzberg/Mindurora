package mindustry.ui.dialogs;

import arc.*;
import arc.scene.ui.*;
import arc.graphics.*;
import arc.scene.ui.layout.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.ui.*;

/**
 * Planet settings for the planet host: address for friends, password, who is playing now.
 * — Налаштування планети для хоста планети: адреса для друзів, пароль, хто зараз грає.
 */
public class AuroraHostDialog extends BaseDialog{

    public AuroraHostDialog(){
        super(AuroraNet.tr("host.title"));
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
            // Access is always this PC + LAN + Tailscale (see AuroraAuthority.allowed) — Доступ завжди цей ПК + LAN + Tailscale (див. AuroraAuthority.allowed)
            t.label(() -> !AuroraAuthority.running() ? AuroraNet.tr("host.stopped") : AuroraNet.tr("host.running")).row();

            separator(t);

            // Address for friends — Адреса для друзів
            String addr = AuroraNet.myIp() + ":" + AuroraNet.myPort();
            t.add(AuroraNet.tr("host.address"));
            t.table(r -> {
                r.add("[accent]" + addr).padRight(8f);
                r.button(Icon.copySmall, Styles.clearNonei, () -> {
                    Core.app.setClipboardText(addr);
                    Vars.ui.showInfoFade("@copied");
                }).size(40f);
            }).row();
            t.add(AuroraNet.tr("host.address.hint")).colspan(2).row();
            t.add(AuroraNet.tr("host.port"));
            t.label(() -> AuroraAuthority.port() > 0 ? String.valueOf(AuroraAuthority.port()) : "-").row();

            // Password (X-Aurora-Token) — Пароль (X-Aurora-Token)
            t.add(AuroraNet.tr("host.password"));
            TextField f = t.field(AuroraNet.hostToken(), AuroraNet::setHostToken).width(260f).get(); // this planet's own password — власний пароль цієї планети
            f.setMaxLength(40);
            t.row();
            t.add(AuroraNet.tr("host.password.hint")).colspan(2).row();

            separator(t);

            // Live stats — Жива статистика
            t.add(AuroraNet.tr("host.live"));
            t.label(() -> String.valueOf(AuroraAuthority.liveCount())).row();
            t.label(() -> {
                var list = AuroraAuthority.liveList();
                return list.isEmpty() ? AuroraNet.tr("nobody") : "[lightgray]" + String.join("\n", list);
            }).colspan(2).left().row();
            t.add(AuroraNet.tr("host.saved"));
            t.label(() -> String.valueOf(AuroraAuthority.savedCount())).row();
            t.add(AuroraNet.tr("host.research"));
            t.label(() -> String.valueOf(AuroraAuthority.unlockCount())).row();

            separator(t);

            // Planet bans: only the planet host bans (from the player list), unban here — Блоки на планеті: банить лише хост планети (зі списку гравців), розблоковувати тут
            var bans = AuroraAuthority.banList();
            t.add(AuroraNet.tr("host.bans")).colspan(2).row();
            if(bans.isEmpty()){
                t.add(AuroraNet.tr("nobody")).colspan(2).row();
            }else{
                bans.forEach((uuid, v) -> {
                    t.add("[lightgray]" + (v[1].isEmpty() ? uuid : v[1]) + (v[0].isEmpty() ? "" : "  " + v[0]));
                    t.button(AuroraNet.tr("unban"), Styles.flatt, () -> {
                        AuroraAuthority.unban(uuid);
                        rebuild();
                    }).size(160f, 40f).row();
                });
            }
        }).width(Math.min(Core.graphics.getWidth() / Scl.scl(1f) - 40f, 560f));
    }
}
