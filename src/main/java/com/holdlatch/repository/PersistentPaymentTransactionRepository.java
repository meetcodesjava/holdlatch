package com.holdlatch.repository;

import com.holdlatch.model.persistence.PaymentTransactionRecord;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersistentPaymentTransactionRepository extends JpaRepository<PaymentTransactionRecord, UUID> {

    Optional<PaymentTransactionRecord> findByStripePaymentIntentId(String paymentIntentId);

    Optional<PaymentTransactionRecord> findByHoldId(String holdId);

    /** Row-locks the payment so two simultaneous webhook deliveries for the same intent are handled one after the other. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransactionRecord p where p.stripePaymentIntentId = :intentId")
    Optional<PaymentTransactionRecord> findByStripePaymentIntentIdForUpdate(@Param("intentId") String intentId);
}
