package mindustry;

import arc.*;
import arc.files.*;
import arc.util.*;
import arc.util.serialization.*;
import arc.net.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

import static mindustry.AuroraAuthority.dir;
import static mindustry.AuroraAuthority.writeAtomic;

/**
 * Authority internals: shared research: what is open on the planet, who opened it, and what each node costs.
 * — Нутрощі Authority: спільні дослідження: що відкрито на планеті, хто відкрив і скільки коштує кожен вузол.
 */
final class AuthorityResearch {

    private AuthorityResearch() {
    }

    // ---------- Host lifecycle — Життєвий цикл хоста ----------

    static final Set<String> unlocked = ConcurrentHashMap.newKeySet();

    /** Who researched what (memory only, for toasts) — Хто що дослідив (лише в пам'яті, для сповіщень). */
    static final ConcurrentHashMap<String, String> unlockedBy = new ConcurrentHashMap<>();

    // ---------- Disk — Диск ----------

    static void saveUnlocks() {
        // Same lock as before the split (AuroraAuthority.class): these writes must not interleave with each other
        // — Той самий замок, що й до поділу (AuroraAuthority.class): ці записи не мають перемішуватись між собою
        synchronized (AuroraAuthority.class) {
            try {
                StringBuilder sb = new StringBuilder();
                for (String s : unlocked) sb.append(s).append('\n');
                writeAtomic(dir().child("unlocked.txt"), sb.toString());
            } catch (Throwable e) {
                Log.err(e);
            }
        }
    }

    // ---------- Research cost — Ціна досліджень ----------

    static volatile Map<String, Map<String, Integer>> costs;

    /** content -> its tech tree parent's content — вміст -> вміст його батька в дереві. */
    static volatile Map<String, String> parents;

    /**
     * Is the node's parent open: no parent, always unlocked, open on this planet, or a free node (items, sectors).
     * — Чи відкритий батько вузла: батька нема, завжди відкритий, відкритий на цій планеті, або безкоштовний вузол (предмети, сектори).
     */
    static boolean parentOpen(String name) {
        researchCost(name); // builds the maps — будує мапи
        String parent = parents.get(name);
        if (parent == null || unlocked.contains(parent) || researchCost(parent) == null) return true;
        var c = Vars.content.byName(parent);
        return c instanceof mindustry.ctype.UnlockableContent u && u.alwaysUnlocked;
    }

    /** Full item cost of researching this content, or null if it has none (items, sectors...) — Повна ціна дослідження в предметах, або null, якщо її нема (предмети, сектори...). */
    static Map<String, Integer> researchCost(String name) {
        Map<String, Map<String, Integer>> c = costs;
        if (c == null) {
            c = new HashMap<>();
            Map<String, String> par = new HashMap<>();
            for (mindustry.content.TechTree.TechNode node : mindustry.content.TechTree.all) {
                if (node.parent != null) par.putIfAbsent(node.content.name, node.parent.content.name);
                Map<String, Integer> m = new HashMap<>();
                for (mindustry.type.ItemStack s : node.requirements) if (s.amount > 0) m.merge(s.item.name, s.amount, Integer::sum);
                if (!m.isEmpty()) c.put(node.content.name, m);
            }
            parents = par;
            costs = c;
        }
        return c.get(name);
    }

    // ---------- Flood limits (this PC is exempt) — Обмеження від флуду (цей ПК не обмежується) ----------

    static volatile Set<String> contentNames;

    /** Is this a real UnlockableContent name (keeps junk out of unlocked.txt) — Чи це справжній UnlockableContent (щоб у unlocked.txt не було сміття). */
    static boolean isContent(String name) {
        Set<String> set = contentNames;
        if (set == null) {
            Set<String> s = new HashSet<>();
            Vars.content.each(c -> {
                if (c instanceof mindustry.ctype.UnlockableContent u) s.add(u.name);
            });
            contentNames = set = s;
        }
        return set.contains(name);
    }
}
