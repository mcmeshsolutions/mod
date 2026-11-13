package dev.mcmesh.core;

import dev.mcmesh.core.GatewayClient.VoteRound;
import dev.mcmesh.core.GatewayClient.VoteState;

/** Plain-language lines for the /meshvote command. */
public final class VoteText {
    private VoteText() { }

    public static String status(VoteState s) {
        if (!s.enabled()) {
            return "Host-change votes are off for this world. The world owner can switch them on.";
        }
        VoteRound r = s.round();
        if (r == null) {
            return "No vote is open. A guest with a clearly stronger machine can start one with /meshvote propose.";
        }
        String votes = r.yes() + " yes, " + r.no() + " no, " + r.needed() + " yes needed";
        if (r.approved()) {
            return r.candidate() + " is approved to take over hosting (" + votes + "). The handover starts on its own.";
        }
        String mine = r.myVote() == null ? "You have not voted: /meshvote yes or /meshvote no." : r.myVote() ? "You voted yes." : "You voted no.";
        return r.candidate() + " proposes to take over hosting with a machine about " + r.improvementPct() + "% faster (" + votes + "). " + mine
                + " Closes in " + r.expiresInS() + " s.";
    }

    /** Wording for a refusal code from the gateway. */
    public static String error(String code) {
        return switch (code) {
            case "VOTE_DISABLED" -> "Host-change votes are off for this world.";
            case "NOT_A_GUEST" -> "Only players who are in the world as guests can vote or propose.";
            case "NO_LIVE_HOST" -> "Nobody can be replaced right now: the world has no running host, or you are the host.";
            case "NOT_BETTER" -> "Your machine is not clearly faster than the host's (it needs to be at least 15% faster).";
            case "TOO_EARLY" -> "The current host started less than a minute ago. Try again shortly.";
            case "ROUND_OPEN" -> "A vote is already open. See /meshvote status.";
            case "COOLDOWN" -> "The last vote for your machine failed. You can propose again after ten minutes.";
            case "NO_ROUND" -> "There is no open vote.";
            case "NOT_MEMBER" -> "You are not a member of this world.";
            default -> "The gateway refused the request (" + code + ").";
        };
    }
}
