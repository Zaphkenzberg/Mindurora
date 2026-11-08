package mindustry.game;

import org.junit.jupiter.api.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The slot prefixes name data already saved on players' devices: changing one silently "loses" that slot's campaign.
 * — Префікси слотів називають дані, вже збережені на пристроях гравців: зміна префікса тихо "губить" кампанію слоту.
 */
public class CampaignSlotsTest{

    @Test
    void prefixesNeverChange(){
        assertEquals("", CampaignSlots.prefix(slotNormal));
        assertEquals("c2-", CampaignSlots.prefix(slotAurora));
        assertEquals("cg-", CampaignSlots.prefix(slotGuest));
    }

    @Test
    void slotsDoNotShareKeys(){
        String base = "serpulo-last-sector";
        assertNotEquals(CampaignSlots.prefix(slotNormal) + base, CampaignSlots.prefix(slotAurora) + base);
        assertNotEquals(CampaignSlots.prefix(slotAurora) + base, CampaignSlots.prefix(slotGuest) + base);
        assertNotEquals(CampaignSlots.prefix(slotNormal) + base, CampaignSlots.prefix(slotGuest) + base);
    }
}
