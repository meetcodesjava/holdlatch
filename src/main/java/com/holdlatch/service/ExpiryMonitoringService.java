package com.holdlatch.service;

import com.holdlatch.config.HoldProperties;
import com.holdlatch.model.domain.PaymentTransactionState;
import com.holdlatch.model.persistence.PaymentTransactionRecord;
import com.holdlatch.payment.PaymentGateway;
import com.holdlatch.repository.IdempotencyLogRepository;
import com.holdlatch.repository.OutboxRepository;
import com.holdlatch.repository.PersistentPaymentTransactionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Passive backstop. Holds expire on their own inside AeroKV; this sweep cleans
 * up what only the database and the payment provider know about: checkouts the
 * buyer abandoned (cancelled at the provider so they can never be paid late)
 * and old bookkeeping rows.
 */
@Service
public class ExpiryMonitoringService {

    private static final Logger log = LoggerFactory.getLogger(ExpiryMonitoringService.class);
    private static final Duration IDEMPOTENCY_RETENTION = Duration.ofHours(24);
    private static final Duration OUTBOX_RETENTION = Duration.ofDays(7);
    private static final Duration SAFETY_MARGIN = Duration.ofMinutes(1);

    private final PersistentPaymentTransactionRepository payments;
    private final SettlementTransactionService settlement;
    private final PaymentGateway gateway;
    private final IdempotencyLogRepository idempotency;
    private final OutboxRepository outbox;
    private final TransactionTemplate tx;
    private final HoldProperties props;
    private final Clock clock;

    ExpiryMonitoringService(PersistentPaymentTransactionRepository payments, SettlementTransactionService settlement, PaymentGateway gateway,
                            IdempotencyLogRepository idempotency, OutboxRepository outbox, PlatformTransactionManager txManager,
                            HoldProperties props, Clock clock) {
        this.payments = payments;
        this.settlement = settlement;
        this.gateway = gateway;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${holdlatch.sweep.interval-ms:60000}")
    public void sweep() {
        abandonCheckouts();
        purgeOldRecords();
    }

    /** Fails payments still unpaid after the hold and its grace period are over, cancelling them at the provider first. */
    public int abandonCheckouts() {
        Instant cutoff = clock.instant().minus(props.ttl()).minus(props.paymentGrace()).minus(SAFETY_MARGIN);
        List<PaymentTransactionRecord> stale = payments.findTop50ByStateAndCreatedAtBeforeOrderByCreatedAt(PaymentTransactionState.PENDING, cutoff);
        int abandoned = 0;
        for (PaymentTransactionRecord payment : stale) {
            try {
                if (payment.getStripePaymentIntentId() != null) {
                    gateway.cancelPaymentIntent(payment.getStripePaymentIntentId());
                }
            } catch (RuntimeException e) {
                // Most likely it was paid a moment ago and its webhook is on the way; leave it for the webhook and check again next sweep.
                log.warn("Not abandoning payment {}: could not cancel at the provider ({})", payment.getId(), e.getMessage());
                continue;
            }
            if (settlement.markFailed(payment.getId(), "checkout abandoned")) {
                abandoned++;
            }
        }
        return abandoned;
    }

    public void purgeOldRecords() {
        Instant now = clock.instant();
        tx.executeWithoutResult(status -> {
            int keys = idempotency.deleteOlderThan(now.minus(IDEMPOTENCY_RETENTION));
            int events = outbox.deleteProcessedBefore(now.minus(OUTBOX_RETENTION));
            if (keys + events > 0) {
                log.info("Purged {} old idempotency records and {} delivered outbox events", keys, events);
            }
        });
    }
}
