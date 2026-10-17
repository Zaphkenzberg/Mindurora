package mindustry;

import arc.*;
import arc.files.*;
import arc.util.*;
import arc.util.serialization.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

/** Вбудований Authority: те саме, що Ktor Main.kt, але всередині гри, на простому HTTP. */
public class AuroraAuthority{
    static final long HOST_TIMEOUT = 30_000L;
    static final long PENDING_TIMEOUT = 12_000L;

    static class Host{
        final String ip, player;
        final int port;
        final long lastSeen;
        final boolean ready;   // true після першого heartbeat: хост уже слухає порт

        Host(String ip, int port, String player, long lastSeen, boolean ready){
            this.ip = ip; this.port = port; this.player = player; this.lastSeen = lastSeen; this.ready = ready;
        }

        boolean alive(long now){
            return now - lastSeen < (ready ? HOST_TIMEOUT : PENDING_TIMEOUT);
        }

        String json(){
            return "{\"ip\":" + q(ip) + ",\"port\":" + port + ",\"player\":" + q(player) + ",\"lastSeen\":" + lastSeen + ",\"ready\":" + ready + "}";
        }
    }

    static HttpServer server;
    static final ConcurrentHashMap<String, Host> hosts = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, byte[]> saves = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, String> infos = new ConcurrentHashMap<>();
    static final Set<String> unlocked = ConcurrentHashMap.newKeySet();

    public static boolean running(){
        return server != null;
    }

    static Fi dir(){
        return Core.settings.getDataDirectory().child("aurora-authority");
    }

    /** Запускає сервер. true, якщо працює (вже був або щойно стартував). */
    public static synchronized boolean start(){
        if(server != null) return true;
        try{
            int port = 8443;
            try{
                int p = new URL(AuroraNet.authority()).getPort();
                if(p > 0) port = p;
            }catch(Exception ignored){}

            load();

            HttpServer s = HttpServer.create(new InetSocketAddress(Core.settings.getString("aurora-bind", "0.0.0.0"), port), 0);
            s.createContext("/", AuroraAuthority::handle);
            s.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "aurora-authority");
                t.setDaemon(true);
                return t;
            }));
            s.start();
            server = s;
            AuroraNet.localAuthority = true;
            Core.settings.put("aurora-authority", "http://127.0.0.1:" + port);

            return true;
        }catch(java.net.BindException e){
            return false;
        }catch(Throwable e){
            Log.err("Aurora Authority failed to start", e);
            return false;
        }
    }

    public static synchronized void stop(){
        if(server == null) return;
        server.stop(0);
        server = null;
        AuroraNet.localAuthority = false;
        hosts.clear();
    }

    // ---------- диск ----------

    static void load(){
        try{
            Fi d = dir();
            if(!d.exists()) return;
            for(Fi f : d.list()){
                String n = f.name();
                if(n.endsWith(".msav")) saves.put(n.substring(0, n.length() - 5), f.readBytes());
                else if(n.endsWith(".info")) infos.put(n.substring(0, n.length() - 5), f.readString());
            }
            Fi u = d.child("unlocked.txt");
            if(u.exists()){
                for(String line : u.readString().split("\n")){
                    if(!line.trim().isEmpty()) unlocked.add(line.trim());
                }
            }
        }catch(Throwable e){
            Log.err("Aurora Authority load failed", e);
        }
    }

    static void saveUnlocks(){
        try{
            StringBuilder sb = new StringBuilder();
            for(String s : unlocked) sb.append(s).append('\n');
            dir().child("unlocked.txt").writeString(sb.toString());
        }catch(Throwable e){
            Log.err(e);
        }
    }

    // ---------- HTTP ----------

    static void handle(HttpExchange ex){
        try{
            String token = Core.settings.getString("aurora-token", "");
            if(!token.isEmpty() && !token.equals(ex.getRequestHeaders().getFirst("X-Aurora-Token"))){
                send(ex, 401, "text/plain", "");
                return;
            }

            URI uri = ex.getRequestURI();
            String m = ex.getRequestMethod();
            String[] p = uri.getPath().split("/");
            String player = query(uri, "player");

            if(m.equals("GET") && p.length == 2 && p[1].equals("unlocks")){
                StringBuilder sb = new StringBuilder("[");
                for(String s : unlocked){
                    if(sb.length() > 1) sb.append(',');
                    sb.append(q(s));
                }
                json(ex, 200, sb.append(']').toString());

            }else if(m.equals("GET") && p.length == 3 && p[1].equals("unlock")){
                unlocked.add(p[2]);
                saveUnlocks();
                json(ex, 200, "{\"name\":" + q(p[2]) + ",\"unlocked\":true}");

            }else if(m.equals("GET") && p.length == 3 && p[1].equals("unlocked")){
                json(ex, 200, "{\"name\":" + q(p[2]) + ",\"unlocked\":" + unlocked.contains(p[2]) + "}");

            }else if(m.equals("GET") && p.length == 2 && p[1].equals("sectors")){
                long now = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder("{");
                for(Map.Entry<String, Host> e : hosts.entrySet()){
                    if(!e.getValue().alive(now)) continue;
                    if(sb.length() > 1) sb.append(',');
                    sb.append(q(e.getKey())).append(':').append(e.getValue().json());
                }
                json(ex, 200, sb.append('}').toString());

            }else if(m.equals("GET") && p.length == 3 && p[1].equals("sectors") && p[2].equals("known")){
                StringBuilder sb = new StringBuilder("[");
                for(String s : saves.keySet()){
                    if(sb.length() > 1) sb.append(',');
                    sb.append(q(s));
                }
                json(ex, 200, sb.append(']').toString());

            }else if(p.length == 4 && p[1].equals("sector") && safe(p[2])){
                sector(ex, m, p[2], p[3], player);

            }else{
                send(ex, 404, "text/plain", "");
            }
        }catch(Throwable e){
            Log.err("Aurora Authority request failed", e);
            try{ send(ex, 400, "text/plain", ""); }catch(Throwable ignored){}
        }finally{
            ex.close();
        }
    }

    static void sector(HttpExchange ex, String m, String id, String action, String player) throws Exception{
        if(m.equals("POST") && action.equals("claim")){
            JsonValue j = new JsonReader().parse(new String(readBody(ex), StandardCharsets.UTF_8));
            long now = System.currentTimeMillis();
            Host mine = new Host(j.getString("ip"), j.getInt("port"), j.getString("player"), now, false);
            Host prev = hosts.get(id);
            Host cur = hosts.compute(id, (k, old) -> old != null && !old.player.equals(mine.player) && old.alive(now) ? old : mine);
            String who = mine.player.substring(0, Math.min(6, mine.player.length()));
            Log.info("Authority claim @ by @ from @ -> @ | prev=@", id, who, mine.ip, cur == mine ? "HOST" : "client",
                    prev == null ? "none" : prev.player.substring(0, Math.min(6, prev.player.length())) + " age=" + (now - prev.lastSeen) + "ms ready=" + prev.ready);
            if(cur == mine) json(ex, 200, "{\"role\":\"host\"}");
            else json(ex, 200, "{\"role\":\"client\",\"ip\":" + q(cur.ip) + ",\"port\":" + cur.port + "}");

        }else if(m.equals("POST") && action.equals("heartbeat")){
            hosts.computeIfPresent(id, (k, h) -> h.player.equals(player) ? new Host(h.ip, h.port, h.player, System.currentTimeMillis(), true) : h);
            send(ex, 200, "text/plain", "");

        }else if(m.equals("POST") && action.equals("release")){
            Host h0 = hosts.get(id);
            boolean own = h0 != null && h0.player.equals(player);
            hosts.computeIfPresent(id, (k, h) -> h.player.equals(player) ? null : h);
            Log.info("Authority release @ by @ -> @", id, player.substring(0, Math.min(6, player.length())), own ? "REMOVED" : "ignored");
            send(ex, 200, "text/plain", "");

        }else if(m.equals("PUT") && action.equals("save")){
            byte[] b = readBody(ex);                       // спершу читаємо тіло
            if(!isHost(id, player)){ send(ex, 403, "text/plain", ""); return; }
            saves.put(id, b);
            dir().child(id + ".msav").writeBytes(b);
            send(ex, 200, "text/plain", "");

        }else if(m.equals("GET") && action.equals("save")){
            byte[] b = saves.get(id);
            if(b == null) send(ex, 404, "text/plain", "");
            else sendBytes(ex, 200, "application/octet-stream", b);

        }else if(m.equals("PUT") && action.equals("info")){
            String s = new String(readBody(ex), StandardCharsets.UTF_8);
            if(!isHost(id, player)){ send(ex, 403, "text/plain", ""); return; }
            infos.put(id, s);
            dir().child(id + ".info").writeString(s);
            send(ex, 200, "text/plain", "");

        }else if(m.equals("GET") && action.equals("info")){
            String s = infos.get(id);
            if(s == null) send(ex, 404, "text/plain", "");
            else json(ex, 200, s);

        }else{
            send(ex, 404, "text/plain", "");
        }
    }

    // ---------- утиліти ----------

    static boolean isHost(String id, String player){
        Host h = hosts.get(id);
        return h != null && h.player.equals(player);
    }

    /** id йде в назву файла, тому тільки безпечні символи. */
    static boolean safe(String id){
        return id.matches("[A-Za-z0-9_\\-]{1,64}");
    }

    static String query(URI uri, String key){
        String qs = uri.getRawQuery();
        if(qs == null) return "";
        for(String kv : qs.split("&")){
            int i = kv.indexOf('=');
            if(i > 0 && kv.substring(0, i).equals(key)){
                try{ return URLDecoder.decode(kv.substring(i + 1), "UTF-8"); }catch(Exception e){ return ""; }
            }
        }
        return "";
    }

    static byte[] readBody(HttpExchange ex) throws IOException{
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        InputStream in = ex.getRequestBody();
        int n;
        while((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String q(String s){
        StringBuilder sb = new StringBuilder("\"");
        for(char c : s.toCharArray()){
            if(c == '"' || c == '\\') sb.append('\\').append(c);
            else if(c < 0x20) sb.append(String.format("\\u%04x", (int)c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static void json(HttpExchange ex, int code, String body) throws IOException{
        send(ex, code, "application/json", body);
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException{
        sendBytes(ex, code, type, body.getBytes(StandardCharsets.UTF_8));
    }

    static void sendBytes(HttpExchange ex, int code, String type, byte[] b) throws IOException{
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
        if(b.length > 0){
            try(OutputStream o = ex.getResponseBody()){ o.write(b); }
        }
    }
}