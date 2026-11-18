package dev.mcmesh.core;

/** What the host machine looks like to the players, from the load level, player count and TPS it reports. */
public record HostHealth(int load, int players, double tps) {
    public enum Status { SMOOTH, STRAINED, OVERLOADED }

    public static final HostHealth UNKNOWN = new HostHealth(-1, -1, -1.0);

    public HostHealth(int load, int players) {
        this(load, players, -1.0);
    }

    public boolean known() {
        return load >= 0;
    }

    public Status status() {
        return load >= 2 || (tps >= 0 && tps < 15.0) ? Status.OVERLOADED :
               load == 1 || (tps >= 0 && tps < 19.0) ? Status.STRAINED : Status.SMOOTH;
    }

    /** Plain words for the HUD; null when the gateway has not heard from the host yet. */
    public String label() {
        if (!known()) {
            return null;
        }
        String word = switch (status()) {
            case SMOOTH -> "running smoothly";
            case STRAINED -> "under load";
            case OVERLOADED -> "overloaded, a stronger machine may take over";
        };
        String tpsPart = tps >= 0 ? String.format("%.1f TPS, ", tps) : "";
        return "Host " + tpsPart + word + (players >= 0 ? " (" + players + (players == 1 ? " player)" : " players)") : "");
    }
}
