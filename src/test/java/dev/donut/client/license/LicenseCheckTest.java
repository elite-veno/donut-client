package dev.donut.client.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Lemon Squeezy's documented answers, and the ones a player can run into, judged for product 4. */
class LicenseCheckTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static String answer(String passed, String status, String expiresAt, long productId, String instance) {
        return """
                {"%s": true, "error": null,
                 "license_key": {"id": 1, "status": "%s", "key": "38b1460a-5104-4067-a91d-77b872934d51",
                   "activation_limit": 2, "activation_usage": 1, "created_at": "2026-10-01T14:15:07.000000Z", "expires_at": %s},
                 "instance": %s,
                 "meta": {"store_id": 1, "order_id": 2, "order_item_id": 3, "product_id": %d, "product_name": "Donut Client",
                   "variant_id": 5, "variant_name": "Default", "customer_id": 6, "customer_name": "A", "customer_email": "a@b.c"}}
                """.formatted(passed, status, expiresAt == null ? "null" : "\"" + expiresAt + "\"",
                instance == null ? "null" : "{\"id\": \"" + instance + "\", \"name\": \"Test\", \"created_at\": \"2026-10-01T14:15:07.000000Z\"}",
                productId);
    }

    @Test
    void activatedKeyOfThisProductPasses() {
        var verdict = LicenseCheck.judge(answer("activated", "active", "2026-12-15T14:15:07.000000Z", 4, "inst-1"), 4, NOW);
        assertTrue(verdict.ok());
        assertEquals("inst-1", verdict.instanceId());
        assertEquals(Instant.parse("2026-12-15T14:15:07Z"), verdict.expiresAt());
        assertTrue(verdict.message().startsWith("Key active until "));
    }

    @Test
    void validKeyWithoutInstanceOrEndDatePasses() {
        var verdict = LicenseCheck.judge(answer("valid", "inactive", null, 4, null), 4, NOW);
        assertTrue(verdict.ok());
        assertNull(verdict.instanceId());
        assertNull(verdict.expiresAt());
    }

    @Test
    void keyOfAnotherProductIsRefused() {
        var verdict = LicenseCheck.judge(answer("valid", "active", null, 99, "inst-1"), 4, NOW);
        assertFalse(verdict.ok());
        assertEquals("That key is for a different product.", verdict.message());
    }

    @Test
    void expiredKeyIsRefusedWithItsDate() {
        var verdict = LicenseCheck.judge(answer("valid", "expired", "2026-10-01T10:00:00.000000Z", 4, "inst-1"), 4, NOW);
        assertFalse(verdict.ok());
        assertTrue(verdict.message().startsWith("This key ran out on "), verdict.message());
    }

    @Test
    void keyPastItsEndDateIsRefusedEvenWhenCalledValid() {
        var verdict = LicenseCheck.judge(answer("valid", "active", "2026-10-15T11:59:00.000000Z", 4, "inst-1"), 4, NOW);
        assertFalse(verdict.ok());
    }

    @Test
    void disabledKeyIsRefused() {
        var verdict = LicenseCheck.judge(answer("valid", "disabled", null, 4, "inst-1"), 4, NOW);
        assertFalse(verdict.ok());
        assertEquals("This key was turned off by the seller.", verdict.message());
    }

    @Test
    void unknownKeyAndActivationLimitReadPlainly() {
        assertEquals("That key doesn't exist. Check it for typos.",
                LicenseCheck.judge("{\"valid\": false, \"error\": \"license_key not found.\", \"license_key\": null, \"meta\": null}", 4, NOW).message());
        assertEquals("This key is already in use on as many PCs as it allows.",
                LicenseCheck.judge("{\"activated\": false, \"error\": \"This license key has reached the activation limit.\"}", 4, NOW).message());
    }

    @Test
    void unreadableAnswerIsRefused() {
        assertFalse(LicenseCheck.judge("<html>Bad gateway</html>", 4, NOW).ok());
        assertFalse(LicenseCheck.judge("", 4, NOW).ok());
    }

    @Test
    void graceCoversThreeDaysButNeverPastTheEndDate() {
        long checked = NOW.minus(Duration.ofDays(2)).toEpochMilli();
        assertTrue(License.withinGrace(new License.Stored("k", "i", "2026-12-01T00:00:00Z", checked), NOW));
        assertTrue(License.withinGrace(new License.Stored("k", "i", null, checked), NOW));
        assertFalse(License.withinGrace(new License.Stored("k", "i", "2026-10-15T11:00:00Z", checked), NOW));
        assertFalse(License.withinGrace(new License.Stored("k", "i", null, NOW.minus(Duration.ofDays(4)).toEpochMilli()), NOW));
        assertFalse(License.withinGrace(new License.Stored("k", null, null, checked), NOW));
    }
}
