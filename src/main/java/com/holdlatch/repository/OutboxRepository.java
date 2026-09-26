package com.holdlatch.repository;

import com.holdlatch.model.persistence.OutboxEventRecord;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxRepository extends JpaRepository<OutboxEventRecord, UUID> {

    /**
     * Claims a batch of due events. SKIP LOCKED lets several dispatcher
     * instances run at once without ever picking the same row, and the lock
     * only lives inside a transaction - hence MANDATORY, so calling this
     * without one fails loudly instead of silently locking nothing.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            select * from outbox_events
             where processed_at is null and next_attempt_at <= :now
             order by created_at
             limit :batchSize
               for update skip locked
            """, nativeQuery = true)
    List<OutboxEventRecord> claimDueBatch(@Param("now") Instant now, @Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventRecord e where e.processedAt is not null and e.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}
