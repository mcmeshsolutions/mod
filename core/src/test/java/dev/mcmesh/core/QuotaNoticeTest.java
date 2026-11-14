package dev.mcmesh.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class QuotaNoticeTest {
    private static GatewayException quota(String code) {
        return new GatewayException(507, code);
    }

    @Test
    void aWorldQuotaRefusalGivesOneMessageAndThenStaysQuietForTenMinutes() {
        QuotaNotice n = new QuotaNotice();
        String first = n.message(quota("QUOTA_STORAGE_WORLD"), 1_000);
        assertNotNull(first);
        assertTrue(first.contains("storage limit") && first.contains("final save"), first);
        assertNull(n.message(quota("QUOTA_STORAGE_WORLD"), 120_000));
        assertNull(n.message(quota("QUOTA_STORAGE_WORLD"), 1_000 + QuotaNotice.INTERVAL_MS - 1));
        assertNotNull(n.message(quota("QUOTA_STORAGE_WORLD"), 1_000 + QuotaNotice.INTERVAL_MS));
    }

    @Test
    void theOwnerQuotaHasItsOwnWording() {
        String m = new QuotaNotice().message(quota("QUOTA_STORAGE_USER"), 5);
        assertTrue(m.startsWith("The owner of this world"), m);
    }

    @Test
    void otherFailuresAreNotQuotaNotices() {
        QuotaNotice n = new QuotaNotice();
        assertNull(n.message(new GatewayException(409, "HEAD_MOVED"), 1_000));
        assertNull(n.message(new IOException("boom"), 1_000));
        assertNotNull(n.message(quota("QUOTA_STORAGE_WORLD"), 1_000), "a different error does not use up the interval");
    }

    @Test
    void isQuotaRecognisesAnyQuotaCode() {
        assertTrue(QuotaNotice.isQuota(quota("QUOTA_WORLD_COUNT")));
        assertFalse(QuotaNotice.isQuota(new GatewayException(500, "OTHER")));
        assertFalse(QuotaNotice.isQuota(new IOException("x")));
    }
}
