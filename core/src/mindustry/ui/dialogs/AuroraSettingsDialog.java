package mindustry.ui.dialogs;

import arc.*;
import arc.util.*;

/**
 * Aurora connection settings for a guest / sector host: planet Authority URL, own IP, password, game port.
 * — Налаштування підключення Aurora для гостя / хоста сектора: адреса Authority планети, своя IP, пароль, порт гри.
 */
public class AuroraSettingsDialog extends BaseDialog{

    public AuroraSettingsDialog(Runnable onClose){
        super("Aurora: налаштування");
        addCloseButton();
        hidden(onClose);

        cont.table(t -> {
            t.defaults().pad(6f).left();

            // Planet host Authority, e.g. http://100.x.x.x:8443 — Authority хоста планети, напр. http://100.x.x.x:8443
            t.add("Authority (URL):");
            t.field(Core.settings.getString("aurora-authority", "http://localhost:8443"), v ->
                    Core.settings.put("aurora-authority", v.trim().replaceAll("/+$", ""))
            ).width(420f).get().setMaxLength(120);
            t.row();

            // IP friends use when we host a sector (empty = auto: Tailscale, then LAN) — IP для друзів, коли ми хост сектора (порожньо = авто: Tailscale, потім LAN)
            t.add("Моя IP (для друзів):");
            t.field(Core.settings.getString("aurora-ip", ""), v ->
                    Core.settings.put("aurora-ip", v.trim())
            ).width(420f).get().setMaxLength(80);
            t.row();

            // Must match the planet host's password — Має збігатися з паролем хоста планети
            t.add("Пароль Aurora:");
            t.field(Core.settings.getString("aurora-token", ""), v ->
                    Core.settings.put("aurora-token", v.trim())
            ).width(420f).get().setMaxLength(40);
            t.row();

            // Game port when we host a sector — Порт гри, коли ми хост сектора
            t.add("Порт хоста:");
            t.field(String.valueOf(Core.settings.getInt("port", 6567)), v ->
                    Core.settings.put("port", Strings.parseInt(v, 6567))
            ).width(420f).get().setMaxLength(5);
            t.row();

            t.add("[lightgray]Authority: адреса хоста планети (http://IP:8443).\n[lightgray]Моя IP: адреса, за якою друзі підключаються до тебе, коли ти хост сектора.\n[lightgray]Пароль: такий самий, як задав хост планети (або порожньо).").colspan(2).left().padTop(10f);
        });
    }
}