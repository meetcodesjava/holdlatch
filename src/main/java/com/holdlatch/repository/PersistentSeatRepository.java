package com.holdlatch.repository;

import com.holdlatch.model.domain.SeatAllocationStatus;
import com.holdlatch.model.persistence.SeatRecord;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PersistentSeatRepository extends JpaRepository<SeatRecord, UUID> {

    /** Just the two ids per seat: enough to know which section a seat is in, without loading whole seat rows. */
    interface SeatSectionRow {
        UUID getId();

        UUID getSectionId();
    }

    @Query("select s.id as id, s.sectionId as sectionId from SeatRecord s where s.eventId = :eventId")
    List<SeatSectionRow> findSeatSections(@Param("eventId") UUID eventId);

    List<SeatRecord> findBySectionIdOrderByRowLabelAscSeatNumberAsc(UUID sectionId);

    List<SeatRecord> findByEventIdAndIdIn(UUID eventId, Collection<UUID> ids);

    List<SeatRecord> findBySectionIdAndRowLabelIn(UUID sectionId, Collection<String> rowLabels);

    long countBySectionIdAndStatus(UUID sectionId, SeatAllocationStatus status);

    /**
     * Single conditional UPDATE: flips only seats that are still AVAILABLE and
     * returns how many it flipped. If that is fewer than the number of seats
     * requested, someone else got there first and the caller must roll back.
     * This is the database's own guard against double-booking, independent of
     * the in-memory hold.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SeatRecord s
               set s.status = com.holdlatch.model.domain.SeatAllocationStatus.BOOKED,
                   s.version = s.version + 1
             where s.id in :ids
               and s.status = com.holdlatch.model.domain.SeatAllocationStatus.AVAILABLE
            """)
    int markBookedIfAvailable(@Param("ids") Collection<UUID> ids);
}
