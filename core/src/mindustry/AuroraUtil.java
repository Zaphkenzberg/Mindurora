package mindustry;

import arc.*;

import java.net.*;

/**
 * Small helpers shared by the Aurora client and Authority: UI text, JSON strings, URL values.
 * — Дрібні помічники, спільні для клієнта Aurora та Authority: текст інтерфейсу, рядки JSON, значення для URL.
 */
public final class AuroraUtil {

    private AuroraUtil() {
    }

    /** UI text from the bundles ("aurora." keys; English by default, Ukrainian in bundle_uk_UA) — Текст інтерфейсу з бандлів (ключі "aurora."; за замовчуванням англійська, українська в bundle_uk_UA). */
    public static String tr(String key, Object... args) {
        return args.length == 0 ? Core.bundle.get("aurora." + key) : Core.bundle.format("aurora." + key, args);
    }

    /** JSON string literal with quotes and escaping; null becomes "" — Рядок JSON у лапках, з екрануванням; null стає "". */
    public static String jsonStr(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /**
     * Aurora's id of a player. A sector server knows a joined player by con.uuid, which is his uuid followed by its CRC32
     * (16 bytes, see Packets.ConnectPacket), while Aurora (claims, bans, kicks) knows him by the bare uuid
     * (platform.getUUID(), 8 bytes). Mixing the two let a kicked player host another sector at once, and a player banned
     * from inside a sector still claim. Anything that is not uuid + matching CRC is returned unchanged.
     * — Id гравця в Aurora. Сервер сектора знає гравця за con.uuid — це його uuid і за ним CRC32 (16 байтів, див.
     * Packets.ConnectPacket), а Aurora (клейми, бани, кіки) знає його за голим uuid (platform.getUUID(), 8 байтів).
     * Через змішування кікнутий гравець одразу хостив інший сектор, а забанений із сектора все одно клеймив.
     * Усе, що не є uuid + відповідний CRC, повертається без змін.
     */
    public static String planetUuid(String uuid) {
        if (uuid == null) return "";
        try {
            byte[] b = arc.util.serialization.Base64Coder.decode(uuid);
            if (b.length == 16) {
                java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                crc.update(b, 0, 8);
                long tail = java.nio.ByteBuffer.wrap(b, 8, 8).getLong();
                if (tail == crc.getValue()) return new String(arc.util.serialization.Base64Coder.encode(java.util.Arrays.copyOf(b, 8)));
            }
        } catch (Exception ignored) {
        }
        return uuid;
    }

    /** URL-encode a query value — Закодувати значення для URL. */
    public static String urlEnc(String s) {
        // The String overload: the Charset one needs Android API 33 — Перевантаження з String: з Charset потребує Android API 33
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
