package dev.mcmesh.core;

/** What the host machine looks like to the players, from the load level and player count it reports. */
public record HostHealth(int load, int players) {
    public enum Status { SMOOTH, STRAINED, OVERLOADED }

    public static final HostHealth UNKNOWN = new HostHealth(-1, -1);

    public boolean known() {
        return load >= 0;
    }

    public Status status() {
        return load >= 2 ? Status.OVERLOADED : load == 1 ? Status.STRAINED : Status.SMOOTH;
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
        return players >= 0 ? "Host " + word + " (" + players + (players == 1 ? " player)" : " players)") : "Host " + word;
    }
}
