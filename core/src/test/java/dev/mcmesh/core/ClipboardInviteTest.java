package dev.mcmesh.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ClipboardInviteTest {

    @BeforeEach
    public void setup() {
        ClipboardInvite.reset();
    }

    @Test
    public void testDetectsJoinAndInviteLinks() {
        String joinUrl = "https://mesh.example.com/join/A1B2C3D4E5";
        var detected = ClipboardInvite.check(joinUrl);
        assertNotNull(detected);
        assertEquals("mesh.example.com", detected.host());
        assertEquals("A1B2C3D4E5", detected.code());
        assertEquals("Join mesh.example.com (Invite: A1B2C3D4E5)", detected.displayText());

        String inviteUrl = "https://play.homestead.xyz/invite/cafebabe1234";
        var detected2 = ClipboardInvite.check(inviteUrl);
        assertNotNull(detected2);
        assertEquals("play.homestead.xyz", detected2.host());
        assertEquals("CAFEBABE1234", detected2.code());
    }

    @Test
    public void testIgnoresUnrelatedClipboardText() {
        assertNull(ClipboardInvite.check(null));
        assertNull(ClipboardInvite.check(""));
        assertNull(ClipboardInvite.check("   \n\t "));
        assertNull(ClipboardInvite.check("https://google.com/search?q=minecraft"));
        assertNull(ClipboardInvite.check("Some random chat message"));
    }

    @Test
    public void testDismissAndMarkHandled() {
        String url = "https://mesh.example.com/join/A1B2C3D4E5";
        var detected = ClipboardInvite.check(url);
        assertNotNull(detected);

        ClipboardInvite.dismiss("A1B2C3D4E5");
        assertTrue(ClipboardInvite.isDismissed("A1B2C3D4E5"));

        // Subsequent check ignores dismissed invite
        assertNull(ClipboardInvite.check(url));

        // After reset, can be detected again
        ClipboardInvite.reset();
        assertNotNull(ClipboardInvite.check(url));
    }
}
