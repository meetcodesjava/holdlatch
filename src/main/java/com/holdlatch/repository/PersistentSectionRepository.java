package com.holdlatch.repository;

import com.holdlatch.model.persistence.SectionRecord;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersistentSectionRepository extends JpaRepository<SectionRecord, UUID> {

    List<SectionRecord> findByEventIdOrderByName(UUID eventId);
}
