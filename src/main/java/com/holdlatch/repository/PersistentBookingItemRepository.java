package com.holdlatch.repository;

import com.holdlatch.model.persistence.BookingItemRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersistentBookingItemRepository extends JpaRepository<BookingItemRecord, UUID> {

    List<BookingItemRecord> findByOrderId(UUID orderId);

    /** Standing-room tickets sold so far in a section, counting only orders that are still confirmed. */
    @Query("""
            select coalesce(sum(i.quantity), 0)
              from BookingItemRecord i, BookingOrderRecord o
             where i.orderId = o.id
               and i.sectionId = :sectionId
               and o.status = com.holdlatch.model.domain.OrderStatus.CONFIRMED
            """)
    long confirmedQuantityForSection(@Param("sectionId") UUID sectionId);
}
