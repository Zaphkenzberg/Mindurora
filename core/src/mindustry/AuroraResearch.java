package mindustry;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.ctype.*;
import mindustry.game.EventType.*;
import mindustry.gen.*;

import static mindustry.Vars.*;
import static mindustry.AuroraNet.myName;
import static mindustry.AuroraNet.tr;
import static mindustry.AuroraNet.q;
import static mindustry.AuroraNet.get;
import static mindustry.AuroraNet.parse;
import static mindustry.AuroraNet.sessionActive;
import mindustry.AuroraNet.Res;

public class AuroraResearch {
    // ---------- Shared research — Спільні дослідження ----------

    /** True while applying unlocks that came from Authority (don't push them back) — true, поки застосовуємо дослідження з Authority (назад не шлемо). */
    private static boolean applyingRemote;

    /**
     * Applies other players' research as real unlocks (with UnlockEvent): build menu and tech tree update at once,
     * a sector host forwards them to its clients (Call.researched), a toast says who researched what.
     * — Застосовує дослідження інших гравців як справжні (з UnlockEvent): меню будівництва й дерево оновлюються одразу,
     * хост сектора пересилає їх своїм клієнтам (Call.researched), сповіщення каже, хто що дослідив.
     */
    static void applyRemoteUnlocks(Seq<UnlockableContent> fresh, ObjectMap<String, String> by) {
        if (fresh.isEmpty()) return;
        applyingRemote = true;
        try {
            if (fresh.size <= 5) {
                for (var u : fresh) {
                    u.unlock();
                    String who = by.get(u.name, "");
                    if (state.isGame() && !who.isEmpty() && !who.equals(myName())) {
                        ui.hudfrag.showToast(new arc.scene.style.TextureRegionDrawable(u.uiIcon), tr("researched", who, u.localizedName));
                    }
                }
            } else {
                // Bulk sync (first join): no flood of toasts — Масова синхронізація (перший вхід): без лавини сповіщень
                for (var u : fresh) {
                    u.quietUnlockLocal(); // not quietUnlock: on a sector client that would skip it — не quietUnlock: у клієнта сектора той пропустив би
                    if (net.server()) Call.researched(u);
                }
                // One event so the build menu and tech tree rebuild — Одна подія, щоб перебудувались меню будівництва й дерево
                Events.fire(new UnlockEvent(fresh.peek()));
                if (state.isGame()) ui.hudfrag.showToast(Icon.book, tr("researched.bulk", String.valueOf(fresh.size)));
            }
        } finally {
            applyingRemote = false;
        }
    }

    /** Unlocks Authority refused (research never paid for); not sent again this session — Дослідження, які Authority відхилив (не оплачені); більше не шлемо в цій сесії. */
    static final java.util.Set<String> refusedUnlocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static void reset() {
        refusedUnlocks.clear();
    }
    static void pushUnlock(String name, String by) throws Exception {
        if (refusedUnlocks.contains(name)) return;
        Res r = get("/unlock/" + q(name) + "?by=" + q(by));
        if (r.code == 403 || r.code == 400) refusedUnlocks.add(name);
    }

    /** Pull shared unlocks from Authority and push ours it does not have yet — Тягне спільні дослідження з Authority і заливає наші, яких там ще нема. */
    public static void syncUnlocks() {
        mainExecutor.submit(() -> {
            try {
                Res res = get("/unlocks");
                if (res.code != 200) return;

                // name -> who researched — назва -> хто дослідив
                ObjectMap<String, String> remote = new ObjectMap<>();
                for (JsonValue c = parse(res).child; c != null; c = c.next) remote.put(c.name, c.asString());

                Core.app.post(() -> {
                    if (!auroraSlot()) return;
                    Seq<String> missing = new Seq<>();
                    Seq<UnlockableContent> fresh = new Seq<>();
                    content.each(c -> {
                        if (!(c instanceof UnlockableContent u)) return;
                        if (remote.containsKey(u.name)) {
                            if (!u.unlockedLocal()) fresh.add(u);
                        } else if (u.unlocked() && !u.alwaysUnlocked && !refusedUnlocks.contains(u.name)) {
                            missing.add(u.name);
                        }
                    });
                    applyRemoteUnlocks(fresh, remote);
                    if (missing.isEmpty()) return;
                    String by = myName();
                    mainExecutor.submit(() -> {
                        for (String n : missing) {
                            try {
                                pushUnlock(n, by);
                            } catch (Exception e) {
                                Log.err("unlock push failed", e);
                                break;
                            }
                        }
                    });
                });
            } catch (Throwable t) {
                Log.err("syncUnlocks failed", t);
            }
        });
    }
    static void init() {
    // Our research -> Authority (not what just came from it, not the normal campaign)
    // — Наші дослідження -> Authority (не те, що саме з нього прийшло, і не звичайна кампанія)
        Events.on(UnlockEvent.class, e -> {
        if (applyingRemote || !sessionActive()) return;
        String n = e.content.name, by = myName();
        mainExecutor.submit(() -> {
            try {
                pushUnlock(n, by);
            } catch (Exception ex) {
                Log.err("unlock push failed", ex);
            }
        });
    });
}

}