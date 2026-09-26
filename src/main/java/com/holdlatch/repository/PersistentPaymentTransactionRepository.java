package com.holdlatch.repository;

import com.holdlatch.model.domain.PaymentTransactionState;
import com.holdlatch.model.persistence.PaymentTransactionRecord;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersistentPaymentTransactionRepository extends JpaRepository<PaymentTransactionRecord, UUID> {

    Optional<PaymentTransactionRecord> findByStripePaymentIntentId(String paymentIntentId);

    Optional<PaymentTransactionRecord> findByHoldId(String holdId);

    List<PaymentTransactionRecord> findTop50ByStateAndCreatedAtBeforeOrderByCreatedAt(PaymentTransactionState state, Instant cutoff);

    /** Row-locks one payment for the duration of the transaction so competing webhooks/sweeps handle it strictly one after another. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransactionRecord p where p.id = :id")
    Optional<PaymentTransactionRecord> findByIdForUpdate(@Param("id") UUID id);

    /** Row-locks the payment so two simultaneous webhook deliveries for the same intent are handled one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransactionRecord p where p.stripePaymentIntentId = :intentId")
    Optional<PaymentTransactionRecord> findByStripePaymentIntentIdForUpdate(@Param("intentId") String intentId);
}
