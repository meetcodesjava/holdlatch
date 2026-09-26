package com.holdlatch.repository;

import com.holdlatch.model.domain.EventStatus;
import com.holdlatch.model.persistence.EventRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersistentEventRepository extends JpaRepository<EventRecord, UUID> {

    Page<EventRecord> findByStatusOrderByStartsAtAsc(EventStatus status, Pageable pageable);

    List<EventRecord> findByOrganizerIdOrderByCreatedAtDesc(UUID organizerId);
}
