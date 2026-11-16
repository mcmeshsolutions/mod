package dev.mcmesh.core;

/** '&amp;' + a legacy formatting code becomes the real section-sign code (U+00A7) Minecraft's font renderer already
 * understands in a plain string. Mirrors gatewayd's own format_codes so a world MOTD set here renders the same way
 * as the gateway MOTD set through the CLI or the panel. */
public final class MotdFormat {
    private static final String CODES = "0123456789abcdefklmnor";

    private MotdFormat() { }

    public static String translate(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length() && CODES.indexOf(Character.toLowerCase(s.charAt(i + 1))) >= 0) {
                out.append('§').append(Character.toLowerCase(s.charAt(i + 1)));
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
