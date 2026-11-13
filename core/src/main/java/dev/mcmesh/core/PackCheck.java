package dev.mcmesh.core;

/** Pack version parsing and the join pre-check wording. */
public final class PackCheck {
    private PackCheck() { }

    /** Dot-separated non-negative integers; null for anything else. */
    public static int[] parse(String v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        String[] parts = v.split("\\.", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("\\d{1,9}")) {
                return null;
            }
            out[i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    public static boolean valid(String v) {
        return parse(v) != null;
    }

    /** Numeric per component, missing components count as 0. Throws on an invalid version. */
    public static int compare(String a, String b) {
        int[] x = parse(a);
        int[] y = parse(b);
        if (x == null || y == null) {
            throw new IllegalArgumentException("not a pack version: " + (x == null ? a : b));
        }
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int c = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Player-facing warning when the world's pack version differs from the local one, null when fine or unknown. */
    public static String warning(String localVersion, String remoteVersion) {
        if (remoteVersion == null || !valid(remoteVersion) || !valid(localVersion)) {
            return null;
        }
        int c = compare(localVersion, remoteVersion);
        if (c == 0) {
            return null;
        }
        return c < 0
                ? "This world runs modpack " + remoteVersion + " but you have " + localVersion + ". Update your modpack before joining."
                : "This world runs modpack " + remoteVersion + " but you have the newer " + localVersion + ". The host needs to update first.";
    }

    /** Readable text for a gateway refusal caused by pack id, null for other errors. */
    public static String explain(GatewayException e, String localPack) {
        if (e.status == 403 && e.code.equals("PACK_MISMATCH")) {
            return "This world belongs to a different modpack than \"" + localPack + "\". Install the pack the world uses.";
        }
        return null;
    }
}
