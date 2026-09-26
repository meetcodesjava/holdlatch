package com.holdlatch.repository;

import com.holdlatch.model.persistence.SectionRecord;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersistentSectionRepository extends JpaRepository<SectionRecord, UUID> {

    List<SectionRecord> findByEventIdOrderByName(UUID eventId);

    List<SectionRecord> findByEventIdAndIdIn(UUID eventId, Collection<UUID> ids);

    /**
     * Atomically claims {@code quantity} standing-room tickets. Returns 1 if
     * they were claimed, 0 if the section does not have that many left - the
     * check and the increment happen in one statement, so two buyers can never
     * both take the last ticket.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SectionRecord s
               set s.soldQuantity = s.soldQuantity + :quantity, s.version = s.version + 1
             where s.id = :sectionId
               and s.soldQuantity + :quantity <= s.capacity
            """)
    int reserveStandingRoom(@Param("sectionId") UUID sectionId, @Param("quantity") int quantity);
}
