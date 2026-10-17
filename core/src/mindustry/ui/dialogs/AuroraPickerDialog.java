package mindustry.ui.dialogs;

import arc.scene.ui.layout.*;
import mindustry.*;
import mindustry.gen.*;
import mindustry.type.*;

public class AuroraPickerDialog extends BaseDialog{
    private Table list = new Table();

    public AuroraPickerDialog(){
        super("Aurora: вибір сектора");
        addCloseButton();
        buttons.button("Оновити", Icon.refresh, this::refresh);
        buttons.button("Налаштування", Icon.settings, () -> new AuroraSettingsDialog(this::refresh).show());
        cont.pane(t -> t.add(list)).grow();
        shown(this::refresh);
    }

    void refresh(){
        list.clear();
        list.add("Завантаження...").pad(10f);

        AuroraNet.fetchSectors(entries -> {
            list.clear();
            if(entries.isEmpty()){
                list.add("Authority недоступний").pad(10f);
                return;
            }
            for(AuroraNet.Entry e : entries){
                Sector s = AuroraNet.sectorById(e.id);
                if(s == null) continue;

                String status = e.hosted ? "[accent]грають: " + e.ip + ":" + e.port : "[lightgray]вільний";
                list.add(s.name() + " [lightgray](" + e.id + ")").left().padRight(20f);
                list.add(status).left().padRight(20f);
                list.button(e.hosted ? "Приєднатись" : "Стати хостом", () -> {
                    hide();
                    AuroraNet.switchTo(s);
                }).size(220f, 50f).pad(4f);
                list.row();
            }
        });
    }
}