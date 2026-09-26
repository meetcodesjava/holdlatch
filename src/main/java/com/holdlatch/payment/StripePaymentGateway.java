package com.holdlatch.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.holdlatch.config.StripeProperties;
import com.holdlatch.exception.ApiException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** PaymentGateway backed by Stripe (test mode with sk_test_ keys). */
public class StripePaymentGateway implements PaymentGateway {

    private final StripeProperties props;
    private final ObjectMapper objectMapper;

    public StripePaymentGateway(StripeProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentIntent createPaymentIntent(long amountCents, String currency, String idempotencyKey, Map<String, String> metadata) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .putAllMetadata(metadata)
                .build();
        try {
            com.stripe.model.PaymentIntent created = com.stripe.model.PaymentIntent.create(params, options(idempotencyKey));
            return new PaymentIntent(created.getId(), created.getClientSecret());
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe could not create the payment: " + e.getMessage(), e);
        }
    }

    @Override
    public void cancelPaymentIntent(String paymentIntentId) {
        try {
            RequestOptions options = options(null);
            com.stripe.model.PaymentIntent.retrieve(paymentIntentId, options).cancel(options);
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe could not cancel " + paymentIntentId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String refund(String paymentIntentId, long amountCents, String idempotencyKey) {
        RefundCreateParams params = RefundCreateParams.builder().setPaymentIntent(paymentIntentId).setAmount(amountCents).build();
        try {
            Refund refund = Refund.create(params, options(idempotencyKey));
            return refund.getId();
        } catch (StripeException e) {
            throw new PaymentGatewayException("Stripe could not refund " + paymentIntentId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public WebhookEvent parseWebhook(String payload, String signatureHeader) {
        try {
            if (signatureHeader == null || !Webhook.Signature.verifyHeader(payload, signatureHeader, props.webhookSecret(), props.webhookToleranceSeconds())) {
                throw invalidSignature();
            }
        } catch (SignatureVerificationException e) {
            throw invalidSignature();
        }

        try {
            JsonNode root = objectMapper.readTree(payload);
            String type = root.path("type").asText("");
            JsonNode object = root.path("data").path("object");
            WebhookEvent.Type mapped = switch (type) {
                case "payment_intent.succeeded" -> WebhookEvent.Type.PAYMENT_SUCCEEDED;
                case "payment_intent.payment_failed" -> WebhookEvent.Type.PAYMENT_FAILED;
                default -> WebhookEvent.Type.OTHER;
            };
            UUID paymentTransactionId = null;
            String fromMetadata = object.path("metadata").path("paymentTransactionId").asText("");
            if (!fromMetadata.isEmpty()) {
                try {
                    paymentTransactionId = UUID.fromString(fromMetadata);
                } catch (IllegalArgumentException ignored) {
                    // malformed metadata: fall back to matching by intent id alone
                }
            }
            return new WebhookEvent(root.path("id").asText(""), mapped, object.path("id").asText(null), paymentTransactionId,
                    object.path("last_payment_error").path("message").asText(null));
        } catch (java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEBHOOK_PAYLOAD", "The webhook body is not valid JSON.");
        }
    }

    private RequestOptions options(String idempotencyKey) {
        RequestOptions.RequestOptionsBuilder builder = RequestOptions.builder()
                .setApiKey(props.secretKey())
                .setConnectTimeout(props.timeoutMs())
                .setReadTimeout(props.timeoutMs());
        if (idempotencyKey != null) {
            builder.setIdempotencyKey(idempotencyKey);
        }
        return builder.build();
    }

    private static ApiException invalidSignature() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SIGNATURE", "Webhook signature verification failed.");
    }
}
