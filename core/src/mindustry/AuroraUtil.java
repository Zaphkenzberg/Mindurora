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
