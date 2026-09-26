package com.holdlatch.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.exception.ApiException;
import com.holdlatch.payment.PaymentGateway;
import com.holdlatch.payment.PaymentGatewayException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.http.HttpStatus;

/** A payment provider that lives in memory: records every call and lets a test decide when things fail. */
public class FakePaymentGateway implements PaymentGateway {

    public static final String VALID_SIGNATURE = "valid-signature";

    public record CreatedIntent(String id, long amountCents, String currency, String idempotencyKey, Map<String, String> metadata) {}

    public record RefundCall(String intentId, long amountCents, String idempotencyKey, String refundId) {}

    public final List<CreatedIntent> created = new CopyOnWriteArrayList<>();
    public final List<String> cancelled = new CopyOnWriteArrayList<>();
    public final List<RefundCall> refunds = new CopyOnWriteArrayList<>();
    public volatile int refundsToFail = 0;
    public volatile boolean failCancel = false;
    public volatile boolean failCreate = false;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void reset() {
        created.clear();
        cancelled.clear();
        refunds.clear();
        refundsToFail = 0;
        failCancel = false;
        failCreate = false;
    }

    @Override
    public PaymentIntent createPaymentIntent(long amountCents, String currency, String idempotencyKey, Map<String, String> metadata) {
        if (failCreate) {
            throw new PaymentGatewayException("provider is down", null);
        }
        // Unique across test contexts: they share one database, and real provider ids are globally unique too.
        String id = "pi_fake_" + UUID.randomUUID();
        created.add(new CreatedIntent(id, amountCents, currency, idempotencyKey, metadata));
        return new PaymentIntent(id, id + "_secret");
    }

    @Override
    public void cancelPaymentIntent(String paymentIntentId) {
        if (failCancel) {
            throw new PaymentGatewayException("cannot cancel " + paymentIntentId, null);
        }
        cancelled.add(paymentIntentId);
    }

    @Override
    public String refund(String paymentIntentId, long amountCents, String idempotencyKey) {
        if (refundsToFail > 0) {
            refundsToFail--;
            throw new PaymentGatewayException("provider is down", null);
        }
        String refundId = "re_fake_" + UUID.randomUUID();
        refunds.add(new RefundCall(paymentIntentId, amountCents, idempotencyKey, refundId));
        return refundId;
    }

    @Override
    public WebhookEvent parseWebhook(String payload, String signatureHeader) {
        if (!VALID_SIGNATURE.equals(signatureHeader)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SIGNATURE", "Webhook signature verification failed.");
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            WebhookEvent.Type type = switch (json.path("kind").asText()) {
                case "succeeded" -> WebhookEvent.Type.PAYMENT_SUCCEEDED;
                case "failed" -> WebhookEvent.Type.PAYMENT_FAILED;
                default -> WebhookEvent.Type.OTHER;
            };
            String tx = json.path("txId").asText("");
            return new WebhookEvent(json.path("id").asText(), type, json.path("intent").asText(null),
                    tx.isEmpty() ? null : UUID.fromString(tx), json.path("message").asText(null));
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_PAYLOAD", "bad json");
        }
    }
}
