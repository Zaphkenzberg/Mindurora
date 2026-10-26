package mindustry;

import arc.net.*;
import arc.net.FrameworkMessage.*;
import arc.util.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * AURORA: transport between the game and Authority over ArcNet (Anuke's TCP library, the same one the game uses on 6567),
 * instead of the old HTTP. One persistent TCP connection per Authority address; requests and responses keep the old
 * "method + path + body -> code + body" shape, so all the Authority logic stays the same.
 * Big bodies (sector saves) are cut into CHUNK-sized frames and glued back on the other side.
 * ArcNet is NOT encrypted: use Tailscale (WireGuard) for anything outside your own LAN.
 * — Транспорт між грою й Authority через ArcNet (TCP-бібліотека Anuke, та сама, що в грі на 6567), замість HTTP.
 * Одне постійне TCP-з'єднання на кожну адресу Authority; запити й відповіді мають стару форму
 * "метод + шлях + тіло -> код + тіло", тож уся логіка Authority лишається тією самою.
 * Великі тіла (сейви секторів) ріжуться на кадри по CHUNK і склеюються на іншому боці.
 * ArcNet НЕ шифрує: для всього поза власною LAN використовуй Tailscale (WireGuard).
 */
public class AuroraWire {
    /** Per-connection write buffer / max single frame / chunk size — Буфер запису з'єднання / найбільший кадр / розмір шматка. */
    public static final int WRITE_BUF = 1 << 20, OBJ_BUF = 64 * 1024, CHUNK = 48 * 1024;
    /** Max glued message (a sector save) — Найбільше склеєне повідомлення (сейв сектора). */
    public static final int MAX_MESSAGE = 64 * 1024 * 1024 + 64 * 1024; // a 64 MB save + headers — сейв 64 МБ + заголовки
    public static final byte REQ = 1, RESP = 2;

    /** One piece of a message on the wire — Один шматок повідомлення в мережі. */
    public static class Frame {
        public byte kind;
        public int id;
        public boolean more;
        public byte[] data;
    }

    // ---------- Serializer — Серіалізатор ----------

    /** Frame + ArcNet's own framework messages (keep-alive, register). Stateless, so thread-safe — Кадр + службові повідомлення ArcNet. Без стану, тож потокобезпечний. */
    public static class Serializer implements NetSerializer {
        @Override
        public int getLengthLength() {
            return 4;
        }

        @Override
        public void writeLength(ByteBuffer b, int length) {
            b.putInt(length);
        }

        @Override
        public int readLength(ByteBuffer b) {
            return b.getInt();
        }

        @Override
        public void write(ByteBuffer b, Object o) {
            if (o instanceof FrameworkMessage m) {
                b.put((byte) -2);
                writeFramework(b, m);
                return;
            }
            Frame f = (Frame) o;
            b.put(f.kind);
            b.putInt(f.id);
            b.put((byte) (f.more ? 1 : 0));
            b.putInt(f.data.length);
            b.put(f.data);
        }

        @Override
        public Object read(ByteBuffer b) {
            byte kind = b.get();
            if (kind == -2) return readFramework(b);
            Frame f = new Frame();
            f.kind = kind;
            f.id = b.getInt();
            f.more = b.get() == 1;
            int n = b.getInt();
            if (n < 0 || n > OBJ_BUF) throw new ArcNetException("Aurora: bad frame length " + n);
            f.data = new byte[n];
            b.get(f.data);
            return f;
        }

        static void writeFramework(ByteBuffer buffer, FrameworkMessage message) {
            if (message instanceof Ping p) {
                buffer.put((byte) 0);
                buffer.putInt(p.id);
                buffer.put(p.isReply ? (byte) 1 : (byte) 0);
            } else if (message instanceof DiscoverHost) {
                buffer.put((byte) 1);
            } else if (message instanceof KeepAlive) {
                buffer.put((byte) 2);
            } else if (message instanceof RegisterUDP p) {
                buffer.put((byte) 3);
                buffer.putInt(p.connectionID);
            } else if (message instanceof RegisterTCP p) {
                buffer.put((byte) 4);
                buffer.putInt(p.connectionID);
            }
        }

        static FrameworkMessage readFramework(ByteBuffer buffer) {
            byte id = buffer.get();
            switch (id) {
                case 0 -> {
                    Ping p = new Ping();
                    p.id = buffer.getInt();
                    p.isReply = buffer.get() == 1;
                    return p;
                }
                case 1 -> {
                    return FrameworkMessage.discoverHost;
                }
                case 2 -> {
                    return FrameworkMessage.keepAlive;
                }
                case 3 -> {
                    RegisterUDP p = new RegisterUDP();
                    p.connectionID = buffer.getInt();
                    return p;
                }
                case 4 -> {
                    RegisterTCP p = new RegisterTCP();
                    p.connectionID = buffer.getInt();
                    return p;
                }
                default -> throw new ArcNetException("Aurora: unknown framework message " + id);
            }
        }
    }

    // ---------- Chunks — Шматки ----------

    /** Glues frames back into messages, per connection — Склеює кадри назад у повідомлення, окремо для кожного з'єднання. */
    public static class Assembler {
        final ConcurrentHashMap<Long, ByteArrayOutputStream> parts = new ConcurrentHashMap<>();
        final int max, maxParts;
        /** Bytes buffered by every Assembler sharing it (null = no global limit) — Байти в буферах усіх Assembler, що його ділять (null = без загального ліміту). */
        final java.util.concurrent.atomic.AtomicLong shared;
        final long sharedMax;
        long mine;

        public Assembler(int max) {
            this(max, 64, null, 0);
        }

        /**
         * max: one message; maxParts: unfinished messages at once; shared/sharedMax: bytes buffered across all connections.
         * Exceeding any of them throws, and the caller closes the connection.
         * — max: одне повідомлення; maxParts: незавершених повідомлень одночасно; shared/sharedMax: байти в буферах усіх з'єднань.
         * Перевищення будь-чого кидає виняток, і викликач закриває з'єднання.
         */
        public Assembler(int max, int maxParts, java.util.concurrent.atomic.AtomicLong shared, long sharedMax) {
            this.max = max;
            this.maxParts = maxParts;
            this.shared = shared;
            this.sharedMax = sharedMax;
        }

        /** Full message when its last frame arrived, else null — Повне повідомлення, коли прийшов останній кадр, інакше null. */
        public synchronized byte[] add(Frame f) throws IOException {
            long key = ((long) f.kind << 32) | (f.id & 0xffffffffL);
            ByteArrayOutputStream o = parts.get(key);
            if (o == null && !f.more) return f.data;
            if (o == null) {
                if (parts.size() >= maxParts) throw new IOException("too many unfinished messages");
                parts.put(key, o = new ByteArrayOutputStream());
            }
            if (o.size() + f.data.length > max) {
                drop(key, o);
                throw new IOException("message too large");
            }
            if (shared != null && shared.addAndGet(f.data.length) > sharedMax) {
                shared.addAndGet(-f.data.length);
                drop(key, o);
                throw new IOException("server busy: too much data being received");
            }
            mine += f.data.length;
            o.write(f.data);
            if (f.more) return null;
            drop(key, o);
            return o.toByteArray();
        }

        void drop(long key, ByteArrayOutputStream o) {
            parts.remove(key);
            release(o.size());
        }

        void release(long n) {
            if (shared == null || n <= 0) return;
            n = Math.min(n, mine);
            mine -= n;
            shared.addAndGet(-n);
        }

        /** Connection closed: give back whatever it still holds — З'єднання закрите: повернути все, що воно ще тримає. */
        public synchronized void clear() {
            parts.clear();
            release(mine);
        }
    }

    /**
     * Sends a message as frames, waiting while the write buffer is full. May sleep: never call on ArcNet's update thread.
     * — Відправляє повідомлення кадрами, чекаючи, поки буфер запису повний. Може спати: ніколи не викликати в потоці оновлення ArcNet.
     */
    public static void send(Connection c, byte kind, int id, byte[] payload) throws IOException {
        long deadline = System.currentTimeMillis() + 20000;
        // One sender at a time per connection, so the free-space check stays true until we write — Один відправник на з'єднання, щоб перевірка місця лишалась правдою до запису
        synchronized (c) {
            int off = 0;
            do {
                int n = Math.min(CHUNK, payload.length - off);
                Frame f = new Frame();
                f.kind = kind;
                f.id = id;
                f.data = Arrays.copyOfRange(payload, off, off + n);
                off += n;
                f.more = off < payload.length;
                while (c.getTcpWriteBufferSize() + n + 64 > WRITE_BUF) {
                    if (!c.isConnected()) throw new SocketException("Aurora: connection closed");
                    if (System.currentTimeMillis() > deadline) throw new SocketTimeoutException("Aurora: send timed out");
                    try {
                        Thread.sleep(2);
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException();
                    }
                }
                c.sendTCP(f);
                if (!c.isConnected()) throw new SocketException("Aurora: connection closed");
            } while (off < payload.length);
        }
    }

    // ---------- Message bodies — Тіла повідомлень ----------

    public static class Req {
        public String method, path, token;
        public int proto;
        public byte[] body;
    }

    public static byte[] encodeReq(String method, String path, String token, int proto, byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(body.length + path.length() + 32);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(method);
        out.writeUTF(path);
        out.writeUTF(token == null ? "" : token);
        out.writeInt(proto);
        out.writeInt(body.length);
        out.write(body);
        return bytes.toByteArray();
    }

    public static Req decodeReq(byte[] b) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        Req r = new Req();
        r.method = in.readUTF();
        r.path = in.readUTF();
        r.token = in.readUTF();
        r.proto = in.readInt();
        int n = in.readInt();
        if (n < 0 || n > b.length) throw new IOException("bad body length");
        r.body = new byte[n];
        in.readFully(r.body);
        return r;
    }

    public static byte[] encodeResp(int code, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(8 + body.length);
        b.putInt(code).putInt(body.length).put(body);
        return b.array();
    }

    /** {code, body} — {код, тіло}. */
    public static Object[] decodeResp(byte[] b) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(b);
        int code = buf.getInt(), n = buf.getInt();
        if (n < 0 || n > buf.remaining()) throw new IOException("bad response length");
        byte[] body = new byte[n];
        buf.get(body);
        return new Object[]{code, body};
    }

    // ---------- Client side: one link per Authority — Бік клієнта: одне з'єднання на Authority ----------

    static final ConcurrentHashMap<String, Link> links = new ConcurrentHashMap<>();

    /** Default Authority port — Порт Authority за замовчуванням. */
    public static final int DEFAULT_PORT = 8443;

    /**
     * Normalizes an Authority address to "host:port" ("[v6]:port" for IPv6). Also reads the old "http://host:port/" form
     * saved by earlier versions; a missing port becomes 8443.
     * — Зводить адресу Authority до "host:port" ("[v6]:port" для IPv6). Читає й стару форму "http://host:port/"
     * з попередніх версій; без порту стає 8443.
     */
    public static String addr(String s) {
        return hostPart(s) + ":" + port(s);
    }

    /** Host for connecting (IPv6 without brackets) — Хост для підключення (IPv6 без дужок). */
    public static String host(String s) {
        String h = hostPart(s);
        return h.startsWith("[") ? h.substring(1, h.length() - 1) : h;
    }

    public static int port(String s) {
        s = strip(s);
        int i;
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            i = end >= 0 && end + 1 < s.length() && s.charAt(end + 1) == ':' ? end + 1 : -1;
        } else {
            i = s.indexOf(':') == s.lastIndexOf(':') ? s.indexOf(':') : -1; // bare IPv6 has many colons and no port — голий IPv6: багато двокрапок, порту нема
        }
        if (i < 0) return DEFAULT_PORT;
        try {
            int p = Integer.parseInt(s.substring(i + 1));
            return p > 0 && p < 65536 ? p : DEFAULT_PORT;
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }

    static String hostPart(String s) {
        s = strip(s);
        if (s.startsWith("[")) {
            int end = s.indexOf(']');
            return end > 0 ? s.substring(0, end + 1) : s + "]";
        }
        int c = s.indexOf(':');
        if (c >= 0 && c == s.lastIndexOf(':')) s = s.substring(0, c);
        else if (c >= 0) s = "[" + s + "]"; // bare IPv6 — голий IPv6
        return s.isEmpty() ? "localhost" : s;
    }

    /** Drops an old "scheme://" prefix and any path — Відкидає старий префікс "scheme://" і шлях. */
    static String strip(String s) {
        s = s == null ? "" : s.trim();
        int i = s.indexOf("://");
        if (i >= 0) s = s.substring(i + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        return s;
    }

    /** Request/response over the shared link to an Authority address ("host:port") — Запит/відповідь через спільне з'єднання з адресою Authority ("host:port"). */
    public static byte[] call(String base, byte[] request, int connectTimeout, int readTimeout) throws IOException {
        String host = host(base);
        int port = port(base);
        Link link = links.computeIfAbsent(host + ":" + port, k -> new Link(host, port));
        return link.call(request, connectTimeout, readTimeout);
    }

    /** Close every link (game exit) — Закрити всі з'єднання (вихід з гри). */
    public static void closeAll() {
        for (Link l : links.values()) l.close();
        links.clear();
    }

    /** Bytes of unfinished answers across all client links, and the cap — Байти незавершених відповідей на всіх клієнтських з'єднаннях і межа. */
    static final java.util.concurrent.atomic.AtomicLong clientAssembling = new java.util.concurrent.atomic.AtomicLong();
    static final long CLIENT_ASSEMBLING_MAX = MAX_MESSAGE + 32L * 1024 * 1024;

    static class Link {
        final String host;
        final int port;
        final ConcurrentHashMap<Integer, CompletableFuture<byte[]>> waiting = new ConcurrentHashMap<>();
        final AtomicInteger ids = new AtomicInteger();
        // A few unfinished answers per link and a cap across all links: a hostile Authority can't fill our memory
        // — Кілька незавершених відповідей на з'єднання і межа на всі з'єднання: ворожий Authority не заб'є нам пам'ять
        final Assembler asm = new Assembler(MAX_MESSAGE, 4, clientAssembling, CLIENT_ASSEMBLING_MAX);
        Client client;

        Link(String host, int port) {
            this.host = host;
            this.port = port;
        }

        synchronized Client ensure(int connectTimeout) throws IOException {
            if (client != null && client.isConnected()) return client;
            close();
            Client c = new Client(WRITE_BUF, OBJ_BUF, new Serializer());
            c.addListener(new NetListener() {
                @Override
                public void received(Connection connection, Object object) {
                    if (!(object instanceof Frame f) || f.kind != RESP) return;
                    // Only answers to requests we are waiting for — Лише відповіді на запити, на які ми чекаємо
                    if (!waiting.containsKey(f.id)) return;
                    try {
                        byte[] full = asm.add(f);
                        if (full == null) return;
                        CompletableFuture<byte[]> fut = waiting.remove(f.id);
                        if (fut != null) fut.complete(full);
                    } catch (IOException e) {
                        Log.err("Aurora link: bad response", e);
                        connection.close(DcReason.error);
                    }
                }

                @Override
                public void disconnected(Connection connection, DcReason reason) {
                    asm.clear();
                    failAll(new SocketException("Aurora: Authority connection closed (" + reason + ")"));
                }
            });
            c.start();
            try {
                c.connect(connectTimeout, host, port);
            } catch (IOException e) {
                try {
                    c.stop();
                } catch (Throwable ignored) {
                }
                throw e;
            }
            client = c;
            return c;
        }

        byte[] call(byte[] request, int connectTimeout, int readTimeout) throws IOException {
            Client c = ensure(connectTimeout);
            int id = ids.incrementAndGet();
            CompletableFuture<byte[]> fut = new CompletableFuture<>();
            waiting.put(id, fut);
            try {
                send(c, REQ, id, request);
                return fut.get(readTimeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new SocketTimeoutException("Aurora: Authority did not answer in " + readTimeout + " ms");
            } catch (ExecutionException e) {
                Throwable t = e.getCause();
                throw t instanceof IOException io ? io : new IOException(t);
            } catch (InterruptedException e) {
                throw new InterruptedIOException();
            } finally {
                waiting.remove(id);
            }
        }

        void failAll(IOException e) {
            for (CompletableFuture<byte[]> f : waiting.values()) f.completeExceptionally(e);
            waiting.clear();
        }

        synchronized void close() {
            if (client == null) return;
            Client c = client;
            client = null;
            try {
                c.stop();
                c.dispose();
            } catch (Throwable ignored) {
            }
            failAll(new SocketException("Aurora: link closed"));
        }
    }
}
