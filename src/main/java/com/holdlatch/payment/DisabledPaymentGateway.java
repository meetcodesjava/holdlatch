package com.holdlatch.payment;

import com.holdlatch.exception.ApiException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Stands in when no Stripe keys are configured, so the rest of the app runs and payment calls fail with a clear 503. */
public class DisabledPaymentGateway implements PaymentGateway {

    @Override
    public PaymentIntent createPaymentIntent(long amountCents, String currency, String idempotencyKey, Map<String, String> metadata) {
        throw notConfigured();
    }

    @Override
    public void cancelPaymentIntent(String paymentIntentId) {
        throw notConfigured();
    }

    @Override
    public String refund(String paymentIntentId, long amountCents, String idempotencyKey) {
        throw notConfigured();
    }

    @Override
    public WebhookEvent parseWebhook(String payload, String signatureHeader) {
        throw notConfigured();
    }

    private static ApiException notConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_NOT_CONFIGURED",
                "Payments are not configured on this server (set STRIPE_SECRET_KEY and STRIPE_WEBHOOK_SECRET).");
    }
}
