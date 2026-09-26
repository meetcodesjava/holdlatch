package com.holdlatch.service;

import com.holdlatch.dto.SettlementDtos.CheckoutResponse;
import com.holdlatch.dto.SettlementDtos.PaymentStatusResponse;
import com.holdlatch.engine.aerokv.AeroKvClient;
import com.holdlatch.exception.ApiException;
import com.holdlatch.exception.HoldExpiredException;
import com.holdlatch.exception.NotFoundException;
import com.holdlatch.model.domain.ReservationHoldToken;
import com.holdlatch.model.persistence.PaymentTransactionRecord;
import com.holdlatch.payment.PaymentGateway;
import com.holdlatch.repository.PersistentBookingRepository;
import com.holdlatch.repository.PersistentPaymentTransactionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Phase 2, first half: prove the hold is still alive, then open a payment for exactly that amount. */
@Service
public class CheckoutService {

    // Stripe refuses charges below this (in the smallest currency unit for USD-like currencies).
    static final long MINIMUM_CHARGE_CENTS = 50;

    private final HoldTokenService tokens;
    private final AeroKvClient aeroKv;
    private final PersistentPaymentTransactionRepository payments;
    private final PersistentBookingRepository orders;
    private final PaymentGateway gateway;
    private final IdempotencyService idempotency;
    private final Clock clock;

    CheckoutService(HoldTokenService tokens, AeroKvClient aeroKv, PersistentPaymentTransactionRepository payments,
                    PersistentBookingRepository orders, PaymentGateway gateway, IdempotencyService idempotency, Clock clock) {
        this.tokens = tokens;
        this.aeroKv = aeroKv;
        this.payments = payments;
        this.orders = orders;
        this.gateway = gateway;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    public IdempotencyService.Result<CheckoutResponse> checkout(UUID userId, String idempotencyKey, String holdToken) {
        return idempotency.execute(userId, idempotencyKey, sha256(holdToken), CheckoutResponse.class, () -> startPayment(userId, holdToken));
    }

    public PaymentStatusResponse status(UUID userId, UUID paymentId) {
        PaymentTransactionRecord payment = payments.findById(paymentId)
                .filter(p -> p.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("PAYMENT_NOT_FOUND", "Payment not found."));
        UUID orderId = orders.findByPaymentTransactionId(paymentId).map(o -> o.getId()).orElse(null);
        return new PaymentStatusResponse(payment.getId(), payment.getState(), orderId, payment.getFailureReason());
    }

    private CheckoutResponse startPayment(UUID userId, String holdTokenString) {
        ReservationHoldToken token = tokens.parse(holdTokenString);
        if (!token.userId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_HOLD_OWNER", "That hold belongs to someone else.");
        }
        if (token.isExpired(clock.instant())) {
            throw new HoldExpiredException();
        }
        // The token's clock is only a first filter; AeroKV, which expires holds itself, is the authority.
        for (String key : token.keys()) {
            if (!aeroKv.get(key).map(token.holdId()::equals).orElse(false)) {
                throw new HoldExpiredException();
            }
        }
        if (token.amountCents() < MINIMUM_CHARGE_CENTS) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AMOUNT_BELOW_MINIMUM",
                    "The payment provider cannot charge less than " + MINIMUM_CHARGE_CENTS + " cents.");
        }
        payments.findByHoldId(token.holdId()).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "CHECKOUT_ALREADY_STARTED", "A payment was already started for this hold.")
                    .with("paymentId", existing.getId());
        });

        // Saved BEFORE the provider is called: if we crash right after charging, the record already exists to reconcile against.
        PaymentTransactionRecord payment = payments.saveAndFlush(new PaymentTransactionRecord(userId, token.eventId(), token.holdId(),
                holdTokenString, token.amountCents(), token.currency(), clock.instant()));

        PaymentGateway.PaymentIntent intent;
        try {
            intent = gateway.createPaymentIntent(token.amountCents(), token.currency(),
                    "checkout-" + payment.getId(), Map.of("paymentTransactionId", payment.getId().toString(), "holdId", token.holdId()));
        } catch (RuntimeException e) {
            // A clean failure means nothing was charged; drop the half-made attempt so the buyer can retry this same hold.
            // (A crash instead of an exception leaves the row in place on purpose, to be reconciled by the webhook/sweep.)
            payments.delete(payment);
            throw e;
        }
        payment.attachPaymentIntent(intent.id());
        payments.save(payment);

        return new CheckoutResponse(payment.getId(), intent.clientSecret(), token.amountCents(), token.currency(), token.expiresAt());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
