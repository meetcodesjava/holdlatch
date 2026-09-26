package com.holdlatch.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.holdlatch.model.persistence.OutboxEventRecord;
import com.holdlatch.support.AbstractPostgresTest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Two dispatcher instances must never be handed the same outbox event. Needs real commits, so no rollback-per-test. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxConcurrencyTest extends AbstractPostgresTest {

    @Autowired OutboxRepository outbox;
    @Autowired PlatformTransactionManager txManager;

    @AfterEach
    void cleanUp() {
        outbox.deleteAll();
    }

    @Test
    void aSecondDispatcherSkipsRowsThatTheFirstOneHasLocked() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 4; i++) {
            outbox.save(new OutboxEventRecord("Order", "id-" + i, "OrderConfirmed", "{}", now.minusSeconds(60)));
        }

        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch firstHasClaimed = new CountDownLatch(1);
        CountDownLatch secondIsDone = new CountDownLatch(1);

        CompletableFuture<List<OutboxEventRecord>> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            List<OutboxEventRecord> mine = outbox.claimDueBatch(now, 2);
            firstHasClaimed.countDown();
            try {
                secondIsDone.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return mine;
        }));

        assertTrue(firstHasClaimed.await(10, TimeUnit.SECONDS));
        List<OutboxEventRecord> second = tx.execute(status -> outbox.claimDueBatch(now, 10));
        secondIsDone.countDown();

        List<OutboxEventRecord> firstBatch = first.get(10, TimeUnit.SECONDS);
        assertEquals(2, firstBatch.size());
        assertEquals(2, second.size(), "second dispatcher should get only the rows the first one did not lock");
        assertTrue(second.stream().noneMatch(e -> firstBatch.stream().anyMatch(f -> f.getId().equals(e.getId()))));
    }
}
