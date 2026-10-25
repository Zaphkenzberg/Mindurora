package mindustry.ui.dialogs;

import arc.*;
import arc.util.*;

/**
 * Aurora connection settings for a guest / sector host: planet Authority address, own IP, password, game port.
 * — Налаштування підключення Aurora для гостя / хоста сектора: адреса Authority планети, своя IP, пароль, порт гри.
 */
public class AuroraSettingsDialog extends BaseDialog{

    public AuroraSettingsDialog(Runnable onClose){
        super(mindustry.AuroraNet.tr("settings.title"));
        addCloseButton();
        hidden(onClose);

        cont.table(t -> {
            t.defaults().pad(6f).left();

            // Planet host Authority "IP:port", e.g. 100.x.x.x:8443; an old "http://..." is read too — Authority хоста планети "IP:порт", напр. 100.x.x.x:8443; стара форма "http://..." теж читається
            t.add("Authority:");
            t.field(mindustry.AuroraWire.addr(Core.settings.getString("aurora-authority", "localhost:8443")), v ->
                    Core.settings.put("aurora-authority", mindustry.AuroraWire.addr(v))
            ).width(420f).get().setMaxLength(120);
            t.row();

            // IP friends use when we host a sector (empty = auto: Tailscale, then LAN) — IP для друзів, коли ми хост сектора (порожньо = авто: Tailscale, потім LAN)
            t.add(mindustry.AuroraNet.tr("settings.myip"));
            t.field(Core.settings.getString("aurora-ip", ""), v ->
                    Core.settings.put("aurora-ip", v.trim())
            ).width(420f).get().setMaxLength(80);
            t.row();

            // Must match the planet host's password — Має збігатися з паролем хоста планети
            t.add(mindustry.AuroraNet.tr("settings.password"));
            t.field(Core.settings.getString("aurora-token", ""), v ->
                    Core.settings.put("aurora-token", v.trim())
            ).width(420f).get().setMaxLength(40);
            t.row();

            // Game port when we host a sector — Порт гри, коли ми хост сектора
            t.add(mindustry.AuroraNet.tr("settings.port"));
            t.field(String.valueOf(Core.settings.getInt("port", 6567)), v ->
                    Core.settings.put("port", Strings.parseInt(v, 6567))
            ).width(420f).get().setMaxLength(5);
            t.row();

            t.add(mindustry.AuroraNet.tr("settings.hint")).colspan(2).left().padTop(10f);
        });
    }
}
