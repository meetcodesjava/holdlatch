package com.holdlatch.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.config.StripeProperties;
import com.holdlatch.exception.ApiException;
import com.holdlatch.payment.PaymentGateway.WebhookEvent;
import com.stripe.net.Webhook;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Exercises the real signature check and payload parsing with genuine Stripe-format signatures; no network involved. */
class StripePaymentGatewayTest {

    private static final String SECRET = "whsec_test_secret_for_unit_tests";
    private final StripePaymentGateway gateway = new StripePaymentGateway(new StripeProperties("sk_test_x", SECRET, 300, 1000), new ObjectMapper());

    private static String signed(String payload) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, SECRET);
    }

    private static String eventJson(String type, String intentId, String extraMetadata) {
        return "{\"id\":\"evt_123\",\"type\":\"" + type + "\",\"data\":{\"object\":{\"id\":\"" + intentId + "\",\"metadata\":{" + extraMetadata
                + "},\"last_payment_error\":{\"message\":\"Your card was declined.\"}}}}";
    }

    @Test
    void acceptsAGenuinelySignedSuccessEventAndExtractsTheFields() throws Exception {
        UUID tx = UUID.randomUUID();
        String payload = eventJson("payment_intent.succeeded", "pi_1", "\"paymentTransactionId\":\"" + tx + "\"");

        WebhookEvent event = gateway.parseWebhook(payload, signed(payload));

        assertEquals(WebhookEvent.Type.PAYMENT_SUCCEEDED, event.type());
        assertEquals("evt_123", event.eventId());
        assertEquals("pi_1", event.paymentIntentId());
        assertEquals(tx, event.paymentTransactionId());
    }

    @Test
    void mapsFailureAndUnknownEventTypes() throws Exception {
        String failed = eventJson("payment_intent.payment_failed", "pi_2", "");
        WebhookEvent failure = gateway.parseWebhook(failed, signed(failed));
        assertEquals(WebhookEvent.Type.PAYMENT_FAILED, failure.type());
        assertEquals("Your card was declined.", failure.failureMessage());
        assertNull(failure.paymentTransactionId());

        String other = eventJson("charge.dispute.created", "dp_1", "");
        assertEquals(WebhookEvent.Type.OTHER, gateway.parseWebhook(other, signed(other)).type());
    }

    @Test
    void malformedMetadataIsIgnoredRatherThanCrashing() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_3", "\"paymentTransactionId\":\"not-a-uuid\"");
        assertNull(gateway.parseWebhook(payload, signed(payload)).paymentTransactionId());
    }

    @Test
    void rejectsATamperedBody() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_1", "");
        String header = signed(payload);
        String tampered = payload.replace("pi_1", "pi_9");
        ApiException e = assertThrows(ApiException.class, () -> gateway.parseWebhook(tampered, header));
        assertEquals("INVALID_SIGNATURE", e.getCode());
    }

    @Test
    void rejectsASignatureMadeWithAnotherSecret() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_1", "");
        String forged = Webhook.Signature.generateSignatureHeader(payload, "whsec_someone_elses_secret");
        assertEquals("INVALID_SIGNATURE", assertThrows(ApiException.class, () -> gateway.parseWebhook(payload, forged)).getCode());
    }

    @Test
    void rejectsAMissingOrGarbageSignatureHeader() {
        String payload = eventJson("payment_intent.succeeded", "pi_1", "");
        assertEquals("INVALID_SIGNATURE", assertThrows(ApiException.class, () -> gateway.parseWebhook(payload, null)).getCode());
        assertEquals("INVALID_SIGNATURE", assertThrows(ApiException.class, () -> gateway.parseWebhook(payload, "garbage")).getCode());
        assertEquals("INVALID_SIGNATURE", assertThrows(ApiException.class, () -> gateway.parseWebhook(payload, "")).getCode());
    }

    @Test
    void rejectsAValidSignatureThatIsTooOldToBeTrusted() throws Exception {
        String payload = eventJson("payment_intent.succeeded", "pi_1", "");
        String stale = Webhook.Signature.generateSignatureHeader(payload, SECRET, Instant.now().minusSeconds(3600).getEpochSecond());
        assertEquals("INVALID_SIGNATURE", assertThrows(ApiException.class, () -> gateway.parseWebhook(payload, stale)).getCode());
    }

    @Test
    void aSignedButNonJsonBodyIsABadRequestNotAServerError() throws Exception {
        String payload = "this is not json";
        ApiException e = assertThrows(ApiException.class, () -> gateway.parseWebhook(payload, signed(payload)));
        assertEquals("INVALID_WEBHOOK_PAYLOAD", e.getCode());
    }

    @Test
    void paymentsAreOnlyEnabledWhenBothKeysArePresent() {
        assertEquals(false, new StripeProperties("", SECRET, 300, 1000).configured());
        assertEquals(false, new StripeProperties("sk_test_x", null, 300, 1000).configured());
        assertEquals(true, new StripeProperties("sk_test_x", SECRET, 300, 1000).configured());
    }
}
