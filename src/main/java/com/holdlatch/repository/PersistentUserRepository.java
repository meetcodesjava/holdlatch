package com.holdlatch.repository;

import com.holdlatch.model.persistence.UserRecord;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PersistentUserRepository extends JpaRepository<UserRecord, UUID> {

    Optional<UserRecord> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);
}
