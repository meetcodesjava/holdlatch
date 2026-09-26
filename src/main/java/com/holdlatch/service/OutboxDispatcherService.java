package com.holdlatch.service;

import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.outbox.OutboxHandler;
import com.holdlatch.repository.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers outbox events (transactional outbox pattern). Events are written in
 * the same transaction as the change that caused them, so a crash between
 * "saved" and "delivered" cannot lose one; this worker delivers each at least
 * once, retrying failures with exponential backoff. Several instances can run
 * at once because claiming rows uses SKIP LOCKED.
 */
@Service
public class OutboxDispatcherService {

    static final int BATCH_SIZE = 10;
    private static final int MAX_BATCHES_PER_RUN = 20;
    private static final int LOUD_FAILURE_ATTEMPTS = 10;
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcherService.class);

    private final OutboxRepository outbox;
    private final Map<String, OutboxHandler> handlers = new HashMap<>();
    private final TransactionTemplate claimTx;
    private final TransactionTemplate handlerTx;
    private final Clock clock;

    OutboxDispatcherService(OutboxRepository outbox, List<OutboxHandler> handlerList, PlatformTransactionManager txManager, Clock clock) {
        this.outbox = outbox;
        handlerList.forEach(h -> handlers.put(h.eventType(), h));
        this.claimTx = new TransactionTemplate(txManager);
        // Each handler gets its own transaction so one failing handler cannot poison the transaction that holds the claim locks.
        this.handlerTx = new TransactionTemplate(txManager);
        this.handlerTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${holdlatch.outbox.poll-interval-ms:1000}")
    public void dispatchPending() {
        for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
            if (dispatchOnce() < BATCH_SIZE) {
                return;
            }
        }
    }

    /** Claims and delivers one batch; returns how many events it picked up. */
    public int dispatchOnce() {
        Integer handled = claimTx.execute(status -> {
            Instant now = clock.instant();
            List<OutboxEventRecord> due = outbox.claimDueBatch(now, BATCH_SIZE);
            for (OutboxEventRecord event : due) {
                deliver(event, now);
                outbox.save(event);
            }
            return due.size();
        });
        return handled == null ? 0 : handled;
    }

    private void deliver(OutboxEventRecord event, Instant now) {
        try {
            OutboxHandler handler = handlers.get(event.getEventType());
            if (handler == null) {
                throw new IllegalStateException("No handler registered for outbox event type " + event.getEventType());
            }
            handlerTx.executeWithoutResult(status -> handler.handle(event));
            event.markProcessed(now);
        } catch (RuntimeException e) {
            event.markFailed(now);
            if (event.getAttempts() >= LOUD_FAILURE_ATTEMPTS) {
                log.error("Outbox event {} ({}) has failed {} times, still retrying: {}", event.getId(), event.getEventType(),
                        event.getAttempts(), e.toString());
            } else {
                log.warn("Outbox event {} ({}) failed (attempt {}), will retry: {}", event.getId(), event.getEventType(),
                        event.getAttempts(), e.toString());
            }
        }
    }
}
