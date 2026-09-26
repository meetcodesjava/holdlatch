package com.holdlatch.service;

import com.holdlatch.config.HoldProperties;
import com.holdlatch.engine.aerokv.AeroKvClient;
import com.holdlatch.engine.aerokv.AeroKvClient.HoldOutcome;
import com.holdlatch.engine.aerokv.AeroKvUnavailableException;
import com.holdlatch.model.domain.PaymentTransactionState;
import com.holdlatch.model.domain.ReservationHoldToken;
import com.holdlatch.model.persistence.PaymentTransactionRecord;
import com.holdlatch.payment.PaymentGateway;
import com.holdlatch.payment.PaymentGateway.WebhookEvent;
import com.holdlatch.repository.PersistentPaymentTransactionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phase 2, second half: turns the payment provider's verdict into a booking or
 * a refund. It never double-books and never double-charges:
 * <ul>
 *   <li>a repeated webhook is a no-op (the payment's state machine has already moved on);</li>
 *   <li>a payment that lands after the hold expired gets a grace period to win its seats back
 *       if nobody else took them, and is otherwise refunded (compensating transaction);</li>
 *   <li>AeroKV network calls happen outside any database transaction.</li>
 * </ul>
 * Failures the provider can safely retry (AeroKV or database briefly down) are allowed to
 * propagate, so the webhook answers 5xx and the provider delivers it again later.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final PaymentGateway gateway;
    private final HoldTokenService tokens;
    private final AeroKvClient aeroKv;
    private final SettlementTransactionService settlement;
    private final PersistentPaymentTransactionRepository payments;
    private final HoldProperties props;
    private final Clock clock;

    PaymentReconciliationService(PaymentGateway gateway, HoldTokenService tokens, AeroKvClient aeroKv,
                                 SettlementTransactionService settlement, PersistentPaymentTransactionRepository payments,
                                 HoldProperties props, Clock clock) {
        this.gateway = gateway;
        this.tokens = tokens;
        this.aeroKv = aeroKv;
        this.settlement = settlement;
        this.payments = payments;
        this.props = props;
        this.clock = clock;
    }

    public void handleWebhook(String payload, String signatureHeader) {
        WebhookEvent event = gateway.parseWebhook(payload, signatureHeader);
        switch (event.type()) {
            case PAYMENT_SUCCEEDED -> onPaymentSucceeded(event);
            case PAYMENT_FAILED -> onPaymentFailed(event);
            case OTHER -> log.debug("Ignoring webhook event {} of an unhandled type", event.eventId());
        }
    }

    private void onPaymentSucceeded(WebhookEvent event) {
        PaymentTransactionRecord payment = locate(event).orElse(null);
        if (payment == null) {
            log.warn("Payment {} succeeded but matches no payment of ours (event {})", event.paymentIntentId(), event.eventId());
            return;
        }
        if (payment.getState() != PaymentTransactionState.PENDING) {
            log.info("Duplicate/late webhook for payment {} already in state {}; nothing to do", payment.getId(), payment.getState());
            return;
        }

        ReservationHoldToken token = tokens.parse(payment.getHoldSnapshot());
        boolean secured = secureHold(token);

        SettlementTransactionService.Result result;
        if (!secured) {
            result = settlement.scheduleRefund(payment.getId(), "hold expired before the payment completed");
        } else {
            try {
                result = settlement.confirm(payment.getId(), token);
            } catch (SettlementTransactionService.SettlementRefusedException refused) {
                result = settlement.scheduleRefund(payment.getId(), refused.getMessage());
            }
        }
        log.info("Payment {} settled as {}", payment.getId(), result);
        releaseQuietly(token);
    }

    private void onPaymentFailed(WebhookEvent event) {
        PaymentTransactionRecord payment = locate(event).orElse(null);
        if (payment == null || payment.getState() != PaymentTransactionState.PENDING) {
            return;
        }
        ReservationHoldToken token = tokens.parse(payment.getHoldSnapshot());
        String reason = event.failureMessage() == null ? "payment failed" : event.failureMessage();
        if (settlement.markFailed(payment.getId(), reason)) {
            // Stop the same intent from succeeding later, then hand the seats straight back to everyone else.
            try {
                gateway.cancelPaymentIntent(payment.getStripePaymentIntentId());
            } catch (RuntimeException e) {
                log.warn("Could not cancel failed payment intent of {}: {}", payment.getId(), e.getMessage());
            }
            releaseQuietly(token);
        }
    }

    /** True if the buyer still holds every key, or managed to win the same ones back within the grace period. */
    private boolean secureHold(ReservationHoldToken token) {
        if (allKeysStillOurs(token)) {
            return true;
        }
        Instant now = clock.instant();
        if (now.isAfter(token.expiresAt().plus(props.paymentGrace()))) {
            return false;
        }
        // Inside the grace period: hand back any keys that are still ours, then try to take the whole set again in one atomic step.
        for (String key : token.keys()) {
            aeroKv.releaseIfOwner(key, token.holdId());
        }
        return aeroKv.multiHold(token.keys(), token.holdId(), props.ttl()) == HoldOutcome.ACQUIRED;
    }

    private boolean allKeysStillOurs(ReservationHoldToken token) {
        for (String key : token.keys()) {
            if (!aeroKv.get(key).map(token.holdId()::equals).orElse(false)) {
                return false;
            }
        }
        return true;
    }

    private Optional<PaymentTransactionRecord> locate(WebhookEvent event) {
        if (event.paymentIntentId() != null) {
            Optional<PaymentTransactionRecord> byIntent = payments.findByStripePaymentIntentId(event.paymentIntentId());
            if (byIntent.isPresent()) {
                return byIntent;
            }
        }
        // We may have crashed after creating the intent but before saving its id; the metadata we attached still identifies it.
        return event.paymentTransactionId() == null ? Optional.empty() : payments.findById(event.paymentTransactionId());
    }

    private void releaseQuietly(ReservationHoldToken token) {
        for (String key : token.keys()) {
            try {
                aeroKv.releaseIfOwner(key, token.holdId());
            } catch (AeroKvUnavailableException e) {
                log.warn("Could not release {} after settlement; it will expire on its own", key);
            }
        }
    }
}
