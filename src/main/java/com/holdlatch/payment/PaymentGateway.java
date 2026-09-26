package com.holdlatch.payment;

import java.util.Map;
import java.util.UUID;

/**
 * Everything HoldLatch needs from a payment provider, kept provider-neutral
 * so the settlement logic can be tested without a network and the provider
 * can be swapped without touching it.
 */
public interface PaymentGateway {

    record PaymentIntent(String id, String clientSecret) {}

    /**
     * A verified provider notification. {@code paymentTransactionId} comes from
     * the metadata we attached at creation, so a payment can still be matched
     * even if we crashed before saving the provider's own intent id.
     */
    record WebhookEvent(String eventId, Type type, String paymentIntentId, UUID paymentTransactionId, String failureMessage) {
        public enum Type { PAYMENT_SUCCEEDED, PAYMENT_FAILED, OTHER }
    }

    PaymentIntent createPaymentIntent(long amountCents, String currency, String idempotencyKey, Map<String, String> metadata);

    /** Stops an unpaid intent from ever succeeding later. Throws if it has already been paid. */
    void cancelPaymentIntent(String paymentIntentId);

    /** Refunds the full amount; the idempotency key makes a repeated call refund only once. Returns the provider's refund id. */
    String refund(String paymentIntentId, long amountCents, String idempotencyKey);

    /** Verifies the signature and parses the notification; throws an ApiException(400) if the signature is wrong. */
    WebhookEvent parseWebhook(String payload, String signatureHeader);
}
