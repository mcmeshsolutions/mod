package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.mcmesh.core.GatewayClient.VoteRound;
import dev.mcmesh.core.GatewayClient.VoteState;
import org.junit.jupiter.api.Test;

class VoteTextTest {
    @Test
    void theGatewayJsonIsParsedIncludingAMissingRoundAndAnUnsetVote() {
        VoteState off = GatewayClient.parseVote(JsonParser.parseString("{\"enabled\":false,\"round\":null}").getAsJsonObject());
        assertEquals(new VoteState(false, null), off);
        VoteState on = GatewayClient.parseVote(JsonParser.parseString(
                "{\"enabled\":true,\"round\":{\"candidate\":\"bob\",\"improvement_pct\":80,\"yes\":1,\"no\":0,\"needed\":2,\"my_vote\":null,\"approved\":false,\"expires_in_s\":290}}").getAsJsonObject());
        assertEquals(new VoteRound("bob", 80, 1, 0, 2, null, false, 290), on.round());
        VoteState proposeReply = GatewayClient.parseVote(JsonParser.parseString(
                "{\"round\":{\"candidate\":\"bob\",\"improvement_pct\":15,\"yes\":2,\"no\":0,\"needed\":2,\"my_vote\":true,\"approved\":true,\"expires_in_s\":300}}").getAsJsonObject());
        assertTrue(proposeReply.enabled(), "a reply without the enabled field means it is on");
        assertEquals(Boolean.TRUE, proposeReply.round().myVote());
    }

    @Test
    void statusLinesSayWhatToDoNext() {
        assertTrue(VoteText.status(new VoteState(false, null)).contains("off"));
        assertTrue(VoteText.status(new VoteState(true, null)).contains("/meshvote propose"));
        String open = VoteText.status(new VoteState(true, new VoteRound("bob", 80, 1, 0, 2, null, false, 290)));
        assertTrue(open.contains("bob") && open.contains("80%") && open.contains("/meshvote yes") && open.contains("290 s"), open);
        assertTrue(VoteText.status(new VoteState(true, new VoteRound("bob", 80, 2, 0, 2, true, true, 290))).contains("approved"));
        assertTrue(VoteText.status(new VoteState(true, new VoteRound("bob", 80, 1, 0, 2, false, false, 5))).contains("You voted no"));
    }

    @Test
    void everyGatewayCodeHasPlainWording() {
        for (String c : new String[] {"VOTE_DISABLED", "NOT_A_GUEST", "NO_LIVE_HOST", "NOT_BETTER", "TOO_EARLY", "ROUND_OPEN", "COOLDOWN", "NO_ROUND", "NOT_MEMBER"}) {
            assertTrue(!VoteText.error(c).contains("refused the request"), c);
        }
        assertTrue(VoteText.error("SOMETHING_ELSE").contains("SOMETHING_ELSE"));
    }
}
