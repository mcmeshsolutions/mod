package dev.mcmesh.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OnboardingWizardTest {

    @Test
    public void testFirstLaunchLifecycle() {
        OnboardingWizard.resetWizard();
        assertTrue(OnboardingWizard.shouldShowWizard(), "Should show wizard on virgin profile");

        OnboardingWizard.markFirstLaunchCompleted();
        assertFalse(OnboardingWizard.shouldShowWizard(), "Should not show wizard once completed");
    }

    @Test
    public void testParseInputGatewayUrlsAndInvites() {
        // Plain URL
        OnboardingWizard.WizardInput plain = OnboardingWizard.parseInput("https://mesh.example.com");
        assertFalse(plain.isInvite());
        assertEquals("https://mesh.example.com", plain.hostOrUrl());

        // Host without scheme
        OnboardingWizard.WizardInput noScheme = OnboardingWizard.parseInput("node1.mcmesh.network");
        assertFalse(noScheme.isInvite());
        assertEquals("https://node1.mcmesh.network", noScheme.hostOrUrl());

        // Web invite URL
        OnboardingWizard.WizardInput webInvite = OnboardingWizard.parseInput("https://play.mesh.io/invite/ABCDEF012345");
        assertTrue(webInvite.isInvite());
        assertEquals("ABCDEF012345", webInvite.inviteCode());
        assertEquals("play.mesh.io", webInvite.hostOrUrl());

        // Scheme invite
        OnboardingWizard.WizardInput schemeInvite = OnboardingWizard.parseInput("mcmesh://join/cluster1.lan/1234567890ABCDEF");
        assertTrue(schemeInvite.isInvite());
        assertEquals("1234567890ABCDEF", schemeInvite.inviteCode());
        assertEquals("cluster1.lan", schemeInvite.hostOrUrl());
    }

    @Test
    public void testDeviceTokenGeneration() {
        String token1 = OnboardingWizard.generateDeviceToken("Player1");
        String token2 = OnboardingWizard.generateDeviceToken("Player1");
        assertNotNull(token1);
        assertEquals(32, token1.length());
        assertNotEquals(token1, token2, "Device tokens must be unique across invocations");
    }
}
