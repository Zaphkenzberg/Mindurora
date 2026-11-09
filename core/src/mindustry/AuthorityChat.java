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

import static mindustry.AuthorityAccess.fromThisPc;
import static mindustry.AuthorityAccess.plainAddr;
import static mindustry.AuthorityBans.removed;
import static mindustry.AuthorityBans.removedJson;
import static mindustry.AuthorityClaims.validSector;
import static mindustry.AuthorityRoutes.Exchange;
import static mindustry.AuthorityRoutes.json;
import static mindustry.AuthorityRoutes.readBody;
import static mindustry.AuthorityRoutes.sector;
import static mindustry.AuthorityRoutes.send;

/**
 * Authority internals: planet chat: messages kept for latecomers, cleaned text and a per-address rate limit.
 * — Нутрощі Authority: чат планети: повідомлення для тих, хто прийде пізніше, очищений текст і обмеження частоти з однієї адреси.
 */
final class AuthorityChat {

    private AuthorityChat() {
    }

    // ---------- Planet chat — Чат планети ----------

    /** Messages kept for players who come later, and the longest message — Скільки повідомлень тримаємо для тих, хто прийде пізніше, і найдовше повідомлення. */
    static final int CHAT_KEEP = 50, CHAT_TEXT = 200, CHAT_NAME = 40;

    /** What a newcomer (since < 0) gets from the history — Скільки історії отримує новенький (since < 0). */
    static final int CHAT_NEWCOMER = 10;

    /** Messages per second per address, and the burst allowed — Повідомлень на секунду з однієї адреси і дозволений сплеск. */
    static final float CHAT_RATE = 0.5f, CHAT_BURST = 5f;

    /** {seq, name, sector, text}, oldest first. Memory only: the chat starts empty after a restart — {номер, ім'я, сектор, текст}, найстаріші спершу. Лише в пам'яті: після перезапуску чат порожній. */
    static final ArrayDeque<String[]> chat = new ArrayDeque<>();

    static long chatSeq;

    static final Object chatLock = new Object();

    /** ip -> {tokens, last refill ms}, like buckets — ip -> {токени, останнє поповнення мс}, як buckets. */
    static final ConcurrentHashMap<String, double[]> chatBuckets = new ConcurrentHashMap<>();

    /** POST /chat?player=uuid {"name","sector","text"}: a message to everyone on the planet — повідомлення всім на планеті. */
    static void chatPost(Exchange ex, String player) throws IOException {
        if (removed(player)) {
            json(ex, 403, removedJson(player));
            return;
        }
        if (!fromThisPc(ex) && !allowChat(plainAddr(ex.getRemoteAddress().getAddress()))) {
            send(ex, 429, ""); // too often — занадто часто
            return;
        }
        JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
        String text = chatClean(j.getString("text", ""), CHAT_TEXT);
        String name = chatClean(j.getString("name", ""), CHAT_NAME);
        String sector = j.getString("sector", "");
        if (!validSector(sector)) sector = "";
        if (text.isEmpty()) {
            send(ex, 400, "");
            return;
        }
        if (name.isEmpty()) name = "?";
        long seq;
        synchronized (chatLock) {
            seq = ++chatSeq;
            chat.addLast(new String[]{String.valueOf(seq), name, sector, text});
            while (chat.size() > CHAT_KEEP) chat.removeFirst();
        }
        json(ex, 200, AuroraJson.obj().put("seq", seq).toString());
    }

    /**
     * GET /chat?since=N: {"seq":last, "msgs":[...]} with messages after N. since < 0 (just joined): the last few.
     * since above our counter (Authority restarted, counter from zero): everything we have.
     * — Повідомлення після N. since < 0 (щойно зайшов): кілька останніх. since більший за наш лічильник
     * (Authority перезапустився, лічильник з нуля): усе, що є.
     */
    static String chatJson(long since) {
        synchronized (chatLock) {
            if (since > chatSeq) since = 0;
            AuroraJson msgs = AuroraJson.arr();
            int skip = since < 0 ? Math.max(0, chat.size() - CHAT_NEWCOMER) : 0, i = 0;
            for (String[] m : chat) {
                if (i++ < skip) continue;
                if (since >= 0 && Long.parseLong(m[0]) <= since) continue;
                msgs.add(AuroraJson.obj().put("seq", Long.parseLong(m[0])).put("name", m[1]).put("sector", m[2]).put("text", m[3]));
            }
            return AuroraJson.obj().put("seq", chatSeq).put("msgs", msgs).toString();
        }
    }

    /** No color tags (nobody can fake a system message), no control chars, trimmed and cut — Без тегів кольору (ніхто не підробить системне повідомлення), без керівних символів, обрізано. */
    static String chatClean(String s, int max) {
        if (s == null) return "";
        s = Strings.stripColors(s).replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Token bucket for chat per address — Відро токенів для чату на адресу. */
    static boolean allowChat(String ip) {
        double[] b = chatBuckets.computeIfAbsent(ip, k -> new double[]{CHAT_BURST, System.currentTimeMillis()});
        synchronized (b) {
            long now = System.currentTimeMillis();
            b[0] = Math.min(CHAT_BURST, b[0] + (now - b[1]) / 1000.0 * CHAT_RATE);
            b[1] = now;
            if (b[0] < 1) return false;
            b[0] -= 1;
            return true;
        }
    }
}
