package mindustry.ui.dialogs;

import arc.*;
import arc.graphics.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.ui.*;

import java.util.*;

/**
 * Planet host only: everyone playing on the planet now (sector hosts and their players) with kick / ban, and the ban list.
 * — Лише для хоста планети: усі, хто зараз грає на планеті (хости секторів і їхні гравці), з кіком / баном, і список заблокованих.
 */
public class AuroraPlayersDialog extends BaseDialog{
    String shownKey = "";
    float timer;

    public AuroraPlayersDialog(){
        super(AuroraNet.tr("players.title"));
        addCloseButton();
        shown(() -> {
            shownKey = "";
            rebuild();
        });
        // Refresh only when the list changed, so scrolling is not reset — Оновлюємо лише коли список змінився, щоб не скидати прокрутку
        update(() -> {
            if((timer += Time.delta) < 60f) return;
            timer = 0f;
            if(!key().equals(shownKey)) rebuild();
        });
    }

    static String key(){
        StringBuilder sb = new StringBuilder();
        for(var p : AuroraAuthority.players()) sb.append(p.sector).append(p.uuid).append(p.host).append(';');
        sb.append('|');
        for(String u : AuroraAuthority.banList().keySet()) sb.append(u).append(';');
        return sb.toString();
    }

    static String sectorName(String id){
        // "serpulo-170" -> "Ground Zero (serpulo)" — "serpulo-170" -> "Ground Zero (serpulo)"
        int dash = id.lastIndexOf('-');
        if(dash > 0){
            Planet pl = Vars.content.planet(id.substring(0, dash));
            int n = Strings.parseInt(id.substring(dash + 1), -1);
            if(pl != null && n >= 0 && n < pl.sectors.size) return pl.sectors.get(n).name() + " [lightgray](" + pl.localizedName + ")";
        }
        return id;
    }

    void rebuild(){
        shownKey = key();
        cont.clear();
        float w = Math.min(Core.graphics.getWidth() / Scl.scl(1f) - 40f, 640f);
        cont.pane(t -> {
            t.top().left().defaults().pad(4f).left();

            var players = AuroraAuthority.players();
            t.add(AuroraNet.tr("players.now")).colspan(3).row();
            if(players.isEmpty()){
                t.add(AuroraNet.tr("nobody")).colspan(3).row();
            }
            String last = null;
            for(var p : players){
                if(!p.sector.equals(last)){
                    last = p.sector;
                    t.add(sectorName(p.sector)).colspan(3).padTop(8f).row();
                    t.image().color(Color.gray).height(2f).growX().colspan(3).row();
                }
                boolean me = AuroraAuthority.isOwner(p.uuid);
                t.add((p.host ? AuroraNet.tr("players.host") : "") + p.name + (me ? AuroraNet.tr("players.you") : "")).growX();
                if(me){
                    t.add().colspan(2).row();
                    continue;
                }
                String uuid = p.uuid, ip = p.ip, name = p.name;
                t.button(AuroraNet.tr("players.kick"), Icon.cancel, Styles.flatt, () -> Vars.ui.showConfirm("@confirm", AuroraNet.tr("players.kick.confirm", name), () -> {
                    AuroraAuthority.kick(uuid);
                    rebuild();
                })).size(110f, 40f);
                t.button(AuroraNet.tr("players.ban"), Icon.hammer, Styles.flatt, () -> Vars.ui.showConfirm("@confirm", AuroraNet.tr("players.ban.confirm", name), () -> {
                    AuroraAuthority.ban(uuid, ip, name, "planet host (list)");
                    rebuild();
                })).size(110f, 40f).row();
            }

            t.add(AuroraNet.tr("players.banned")).colspan(3).padTop(16f).row();
            var bans = AuroraAuthority.banList();
            if(bans.isEmpty()){
                t.add(AuroraNet.tr("nobody")).colspan(3).row();
            }
            for(Map.Entry<String, String[]> e : bans.entrySet()){
                String uuid = e.getKey();
                String[] v = e.getValue();
                t.add((v[1].isEmpty() ? uuid : v[1]) + (v[0].isEmpty() ? "" : " [lightgray]" + v[0])).growX();
                t.add();
                t.button(AuroraNet.tr("unban"), Styles.flatt, () -> {
                    AuroraAuthority.unban(uuid);
                    rebuild();
                }).size(160f, 40f).row();
            }
            t.add(AuroraNet.tr("players.hint")).colspan(3).padTop(12f).row();
        }).width(w).growY();
    }
}
