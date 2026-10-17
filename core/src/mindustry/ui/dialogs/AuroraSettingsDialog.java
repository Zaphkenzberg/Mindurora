package mindustry.ui.dialogs;

import arc.*;
import arc.util.*;

public class AuroraSettingsDialog extends BaseDialog{

    public AuroraSettingsDialog(Runnable onClose){
        super("Aurora: налаштування");
        addCloseButton();
        hidden(onClose);

        cont.table(t -> {
            t.defaults().pad(6f).left();

            t.add("Authority (URL):");
            t.field(Core.settings.getString("aurora-authority", "https://localhost:8443"), v ->
                    Core.settings.put("aurora-authority", v.trim().replaceAll("/+$", ""))
            ).width(420f).get().setMaxLength(120);
            t.row();

            t.add("Моя IP (для друзів):");
            t.field(Core.settings.getString("aurora-ip", "127.0.0.1"), v ->
                    Core.settings.put("aurora-ip", v.trim())
            ).width(420f).get().setMaxLength(80);
            t.row();

            t.add("Порт хоста:");
            t.field(String.valueOf(Core.settings.getInt("port", 6567)), v ->
                    Core.settings.put("port", Strings.parseInt(v, 6567))
            ).width(420f).get().setMaxLength(5);
            t.row();

            t.add("[lightgray]Authority: адреса машини, де запущений Ktor-сервер.\n[lightgray]Моя IP: адреса, за якою друзі підключаються до тебе, коли ти хост.").colspan(2).left().padTop(10f);
        });
    }
}