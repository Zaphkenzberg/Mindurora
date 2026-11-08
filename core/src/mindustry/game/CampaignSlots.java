package mindustry.game;

import mindustry.*;
import mindustry.ctype.*;
import mindustry.type.*;

import static mindustry.Vars.*;

/**
 * AURORA: campaign slots. Each slot has its own save folder and its own settings keys (a prefix), so the normal campaign,
 * the own Aurora planet and a guest visit never share research, sectors or map state.
 * — AURORA: слоти кампаній. У кожного слоту своя папка сейвів і свої ключі налаштувань (префікс), тож звичайна кампанія,
 * своя планета Aurora і візит у гості ніколи не ділять дослідження, сектори чи стан карти.
 */
public final class CampaignSlots{

    private CampaignSlots(){
    }

    /**
     * Settings key prefix of a slot. Never change these: they name data already saved on players' devices.
     * — Префікс ключів налаштувань слоту. Ніколи не міняти: ними названі дані, вже збережені на пристроях гравців.
     */
    public static String prefix(int slot){
        return slot == slotNormal ? "" : slot == slotGuest ? "cg-" : "c2-";
    }

    /** Settings key of the current slot — Ключ налаштувань поточного слоту. */
    public static String key(String base){
        return prefix(campaignSlot) + base;
    }

    /** Switch campaign slot: save folder, keys, unlocks, research progress — Перемкнути слот кампанії: папка сейвів, ключі, дослідження, прогрес. */
    public static void switchTo(int slot){
        // Same slot: everything is already loaded (guest is the exception, it is wiped before each join) — Той самий слот: усе вже завантажено (виняток — гість, його стирають перед кожним входом)
        if(slot == campaignSlot && slot != slotGuest && saveDirectory != null && saveDirectory.equals(dataDirectory.child(campaignSaveDir(slot)))) return;
        campaignSlot = slot;
        saveDirectory = dataDirectory.child(campaignSaveDir(slot));
        universe.reloadForSlot();

        for(Planet p : content.planets()){
            for(Sector s : p.sectors) s.save = null;
        }

        control.saves.load();

        for(Planet p : content.planets()){
            for(Sector s : p.sectors) s.loadInfo();
            p.loadRules(); // own difficulty per slot — своя складність для кожного слоту
        }
        content.each(c -> {
            if(c instanceof UnlockableContent u) u.reloadUnlock();
        });
        if(slot != slotNormal) AuroraResearch.syncUnlocks();
        for(var node : mindustry.content.TechTree.all) node.reloadProgress();
        AuroraNet.onCampaignSlot(slot);
    }
}
