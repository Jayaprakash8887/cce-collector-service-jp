package org.openphc.cce.collector.domain.repository;

import org.openphc.cce.collector.domain.model.SourceRegistration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceRegistrationRepository extends JpaRepository<SourceRegistration, UUID> {

    Optional<SourceRegistration> findBySourceUri(String sourceUri);

    Optional<SourceRegistration> findBySourceUriAndActiveTrue(String sourceUri);

    List<SourceRegistration> findByActiveTrue();

    boolean existsBySourceUri(String sourceUri);
}
