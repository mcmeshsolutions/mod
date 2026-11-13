package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class InviteLinkTest {
    private static final String CODE = "A1B2C3D4E5";

    @Test
    void theLinkTheGatewayPageUsesRoundTrips() {
        String link = InviteLink.link("https://play.example.org/", CODE);
        assertEquals("https://play.example.org/invite/" + CODE, link);
        assertEquals(new InviteLink.Parsed("play.example.org", CODE), InviteLink.parse(link));
    }

    @Test
    void allThreeFormsAreRecognisedWithOrWithoutNoise() {
        assertEquals(new InviteLink.Parsed(null, CODE), InviteLink.parse(CODE));
        assertEquals(new InviteLink.Parsed(null, CODE), InviteLink.parse("  " + CODE.toLowerCase() + "\n"));
        assertEquals(new InviteLink.Parsed(null, CODE), InviteLink.parse("\"" + CODE + "\"."));
        assertEquals(new InviteLink.Parsed("gw.example.org", CODE), InviteLink.parse("mcmesh://join/gw.example.org/" + CODE));
        assertEquals(new InviteLink.Parsed("gw.example.org", CODE), InviteLink.parse("mcmesh://join/GW.Example.org:8443/" + CODE));
        assertEquals(new InviteLink.Parsed("gw.example.org", CODE), InviteLink.parse("Join my world: http://gw.example.org:8080/invite/" + CODE + " see you!"));
    }

    @Test
    void ordinaryClipboardTextIsNotAnInvite() {
        for (String t : new String[] {null, "", "hello world", "https://example.org/", "https://example.org/invite/short", "12345", "mcmesh://join/host/xyz", "A1B2C3D4E5 and more text"}) {
            assertNull(InviteLink.parse(t), String.valueOf(t));
        }
    }

    @Test
    void anInviteIsMatchedToTheConfiguredGatewayByHost() {
        var main = new GatewayList.Gateway("default", "https://play.example.org");
        var other = new GatewayList.Gateway("friend", "https://friend.example.net");
        List<GatewayList.Gateway> all = List.of(main, other);
        assertEquals(other, InviteLink.gatewayFor(InviteLink.parse("https://friend.example.net/invite/" + CODE), all, main));
        assertEquals(main, InviteLink.gatewayFor(InviteLink.parse(CODE), all, main), "a bare code goes to the gateway being looked at");
        assertNull(InviteLink.gatewayFor(InviteLink.parse("https://stranger.example.com/invite/" + CODE), all, main), "never redeem on a gateway the player did not add");
    }
}
