


package mindustry;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.type.*;
import java.nio.charset.StandardCharsets;

import static mindustry.Vars.*;

import static mindustry.AuroraNet.sessionActive;
import static mindustry.AuroraNet.authority;
import static mindustry.AuroraNet.current;
import static mindustry.AuroraNet.me;
import static mindustry.AuroraNet.myName;
import static mindustry.AuroraNet.sid;
import static mindustry.AuroraNet.js;
import static mindustry.AuroraNet.q;
import static mindustry.AuroraNet.tr;
import static mindustry.AuroraNet.parse;
import static mindustry.AuroraNet.request;
import static mindustry.AuroraNet.get;
import mindustry.AuroraNet.Res;

    public class AuroraChat {
        // ---------- Planet chat — Чат планети ----------

        /** Chat prefix that sends a message to the whole planet instead of this sector — Префікс чату, що шле повідомлення всій планеті, а не цьому сектору. */
        public static final String CHAT_PREFIX = "/p";
        /** Last message number shown; -1 = not asked yet on this planet — Номер останнього показаного повідомлення; -1 = на цій планеті ще не питали. */
        static volatile long chatSince = -1;
        /** Authority the chat counter belongs to: another planet starts over — Authority, якому належить лічильник: інша планета — з початку. */
        static volatile String chatBase = "";
        static volatile boolean chatBusy;

        /** Chat input (main thread): "/p text" goes to the planet. True if it was ours — Введення чату (головний потік): "/p текст" іде планеті. true, якщо це наше. */
        public static boolean planetChat(String message) {
            if (!sessionActive()) return false;
            if (!(message.equals(CHAT_PREFIX) || message.startsWith(CHAT_PREFIX + " "))) return false;
            String text = message.substring(CHAT_PREFIX.length()).trim();
            if (text.isEmpty()) {
                ui.chatfrag.addMessage(tr("chat.usage"));
                return true;
            }
            Sector s = current;
            String body = "{\"name\":" + js(myName()) + ",\"sector\":" + js(s == null ? "" : sid(s)) + ",\"text\":" + js(text) + "}";
            String path = "/chat?player=" + q(me());
            mainExecutor.submit(() -> {
                String err = null;
                try {
                    Res r = request("POST", path, body.getBytes(StandardCharsets.UTF_8));
                    if (r.code == 429) err = tr("chat.fast");
                    else if (r.code == 403) err = tr("chat.removed");
                    else if (r.code != 200) err = tr("chat.failed");
                } catch (Exception e) {
                    Log.err("planet chat send failed", e);
                    err = tr("chat.failed");
                }
                String shown = err;
                // Our own message comes back with the next poll, so everyone sees one order — Своє повідомлення повертається з наступним опитуванням, тож у всіх однаковий порядок
                Core.app.post(() -> {
                    if (shown != null) ui.chatfrag.addMessage(shown);
                    else pollChat();
                });
            });
            return true;
        }

        /** Fetch new planet messages into the chat (main thread, every 2 s while in a game) — Забрати нові повідомлення планети в чат (головний потік, кожні 2 с у грі). */
        static void pollChat() {
            if (chatBusy || !sessionActive() || !state.isGame() || ui == null || ui.chatfrag == null) return;
            String base = authority();
            if (!base.equals(chatBase)) {
                chatBase = base;
                chatSince = -1;
            }
            long since = chatSince;
            chatBusy = true;
            mainExecutor.submit(() -> {
                try {
                    Res r = get("/chat?since=" + since);
                    if (r.code != 200) {
                        // Old Authority without chat (404) or no connection: quietly try later — Старий Authority без чату (404) або нема зв'язку: тихо пробуємо пізніше
                        chatBusy = false;
                        return;
                    }
                    JsonValue v = parse(r);
                    long seq = v.getLong("seq", since);
                    Seq<String> lines = new Seq<>();
                    JsonValue msgs = v.get("msgs");
                    for (JsonValue c = msgs == null ? null : msgs.child; c != null; c = c.next) lines.add(chatLine(c));
                    Core.app.post(() -> {
                        // Counter and messages change together, here, so the next poll can't repeat them — Лічильник і повідомлення міняються разом, тут, тож наступне опитування їх не повторить
                        if (base.equals(chatBase)) {
                            if (since < 0) ui.chatfrag.addMessage(tr("chat.usage"));
                            for (String l : lines) ui.chatfrag.addMessage(l);
                            chatSince = seq;
                        }
                        chatBusy = false;
                    });
                } catch (Throwable t) {
                    chatBusy = false;
                }
            });
        }

        /** Chat tag color per planet: orange by default, a soft red for Erekir — Колір тегу чату для планети: за замовчуванням помаранчевий, для Ерекіра м'який червоний. */
        static String chatColor(@Nullable Planet pl) {
            return pl != null && pl.name.equals("erekir") ? "[#ff7f7f]" : "[orange]";
        }

        /**
         * "[Serpulo] Name (Sector): text": the tag names the planet the message came from (players of one Authority can be on
         * Serpulo and Erekir at once). Colors stripped again: the Authority may be someone else's.
         * — Тег називає планету, з якої прийшло повідомлення (гравці одного Authority можуть бути на Серпуло й Ерекірі
         * одночасно). Кольори прибираємо ще раз: Authority може бути чужий.
         */
        static String chatLine(JsonValue c) {
            String id = c.getString("sector", "");
            Planet pl = planetOf(id);
            Sector sec = sectorOf(pl, id);
            String tag = pl == null ? tr("chat.prefix") : "[[" + Strings.stripColors(pl.localizedName) + "]";
            String where = sec == null ? "" : " (" + Strings.stripColors(sec.name()) + ")";
            String name = Strings.stripColors(c.getString("name", "?"));
            String text = Strings.stripColors(c.getString("text", ""));
            return chatColor(pl) + tag + "[] [lightgray]" + name + where + ":[] " + text;
        }

        /** "serpulo-15" -> Serpulo, null if unknown — null, якщо невідома. */
        static @Nullable Planet planetOf(String id) {
            int d = id.lastIndexOf('-');
            return d <= 0 ? null : content.planet(id.substring(0, d));
        }

        /** "serpulo-15" -> sector 15 of that planet, null if unknown — сектор 15 цієї планети, null якщо невідомий. */
        static @Nullable Sector sectorOf(@Nullable Planet pl, String id) {
            if (pl == null) return null;
            int i = Strings.parseInt(id.substring(id.lastIndexOf('-') + 1), -1);
            return i < 0 || i >= pl.sectors.size ? null : pl.sectors.get(i);
        }

    }