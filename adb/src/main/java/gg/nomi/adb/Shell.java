package gg.nomi.adb;

public final class Shell {
    private Shell() {}

    public static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public static String join(String... words) {
        StringBuilder out = new StringBuilder();
        for (String word : words) {
            if (out.length() > 0) out.append(' ');
            out.append(quote(word));
        }
        return out.toString();
    }
}
