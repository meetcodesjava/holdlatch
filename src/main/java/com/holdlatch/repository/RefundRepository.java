package com.holdlatch.repository;

import com.holdlatch.model.persistence.RefundRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<RefundRecord, UUID> {

    List<RefundRecord> findByPaymentTransactionId(UUID paymentTransactionId);
}
