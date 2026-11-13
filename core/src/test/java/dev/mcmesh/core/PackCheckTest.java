package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackCheckTest {
    @Test
    void missingComponentsCountAsZero() {
        assertEquals(0, PackCheck.compare("1.4", "1.4.0"));
        assertEquals(0, PackCheck.compare("1.4.0.0", "1.4"));
        assertEquals(0, PackCheck.compare("0", "0.0"));
        assertTrue(PackCheck.compare("1.4.1", "1.4") > 0);
    }

    @Test
    void comparesNumericallyNotLexically() {
        assertTrue(PackCheck.compare("1.10", "1.9") > 0);
        assertTrue(PackCheck.compare("1.9", "1.10") < 0);
        assertTrue(PackCheck.compare("2", "1.99.99") > 0);
    }

    @Test
    void garbageIsRejected() {
        for (String bad : new String[] {"", "a.b", "1..2", "1.2.", ".1", "-1", "1.-2", "1.2-beta", " 1", "99999999999", null}) {
            assertFalse(PackCheck.valid(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> PackCheck.compare("x", "1"));
        assertThrows(IllegalArgumentException.class, () -> PackCheck.compare("1", "1.x"));
    }

    @Test
    void warningOnlyWhenVersionsKnownAndDifferent() {
        assertNull(PackCheck.warning("1.4", "1.4.0"));
        assertNull(PackCheck.warning("1.4", null));
        assertNull(PackCheck.warning("1.4", "junk"));
        assertNull(PackCheck.warning("junk", "1.4"));
        assertTrue(PackCheck.warning("1.3", "1.4").contains("Update your modpack"));
        assertTrue(PackCheck.warning("1.5", "1.4").contains("host needs to update"));
    }

    @Test
    void packMismatchIsExplained() {
        assertNotNull(PackCheck.explain(new GatewayException(403, "PACK_MISMATCH"), "mine"));
        assertTrue(PackCheck.explain(new GatewayException(403, "PACK_MISMATCH"), "mine").contains("\"mine\""));
        assertNull(PackCheck.explain(new GatewayException(403, "FORBIDDEN"), "mine"));
        assertNull(PackCheck.explain(new GatewayException(500, "PACK_MISMATCH"), "mine"));
    }
}
