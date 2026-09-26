package com.holdlatch.repository;

import com.holdlatch.model.persistence.BookingOrderRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersistentBookingRepository extends JpaRepository<BookingOrderRecord, UUID> {

    List<BookingOrderRecord> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Optional<BookingOrderRecord> findByPaymentTransactionId(UUID paymentTransactionId);
}
