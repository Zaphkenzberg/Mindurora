package mindustry;

/**
 * Tiny JSON builder for Aurora's wire messages: keys and string values are always escaped and commas always land in the
 * right place, so a message can't be broken by a missed quote or comma (which the compiler would never catch).
 * Usage: AuroraJson.obj().put("role", "host").put("retry", 5).toString()
 * — Крихітний збирач JSON для повідомлень Aurora: ключі й рядкові значення завжди екрануються, а коми завжди стають куди
 * треба, тож повідомлення не зламає пропущена лапка чи кома (яких компілятор ніколи не впіймає).
 */
public final class AuroraJson {
    private final StringBuilder sb = new StringBuilder();
    private final char close;
    private boolean empty = true;
    private String done;

    private AuroraJson(char open, char close) {
        sb.append(open);
        this.close = close;
    }

    /** New object {...} — Новий об'єкт {...}. */
    public static AuroraJson obj() {
        return new AuroraJson('{', '}');
    }

    /** New array [...] — Новий масив [...]. */
    public static AuroraJson arr() {
        return new AuroraJson('[', ']');
    }

    private void next(String key) {
        if (done != null) throw new IllegalStateException("AuroraJson already finished");
        if (!empty) sb.append(',');
        empty = false;
        if (key != null) sb.append(AuroraUtil.jsonStr(key)).append(':');
    }

    /** Object field — Поле об'єкта. */
    public AuroraJson put(String key, String value) {
        next(key);
        sb.append(AuroraUtil.jsonStr(value));
        return this;
    }

    public AuroraJson put(String key, long value) {
        next(key);
        sb.append(value);
        return this;
    }

    public AuroraJson put(String key, boolean value) {
        next(key);
        sb.append(value);
        return this;
    }

    public AuroraJson put(String key, AuroraJson value) {
        next(key);
        sb.append(value);
        return this;
    }

    /** Field whose value is JSON that is already built (e.g. by arc's Json) — Поле, значення якого — уже готовий JSON (напр. від Json з arc). */
    public AuroraJson putRaw(String key, String json) {
        next(key);
        sb.append(json);
        return this;
    }

    /** Array element — Елемент масиву. */
    public AuroraJson add(String value) {
        return put(null, value);
    }

    public AuroraJson add(long value) {
        return put(null, value);
    }

    public AuroraJson add(AuroraJson value) {
        return put(null, value);
    }

    /** Finishes on the first call; later calls return the same text — Завершує при першому виклику; далі повертає той самий текст. */
    @Override
    public String toString() {
        if (done == null) done = sb.append(close).toString();
        return done;
    }
}
