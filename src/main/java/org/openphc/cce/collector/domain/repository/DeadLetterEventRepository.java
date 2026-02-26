package org.openphc.cce.collector.domain.repository;

import org.openphc.cce.collector.domain.model.DeadLetterEvent;
import org.openphc.cce.collector.domain.model.enums.RejectionReason;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {

    Page<DeadLetterEvent> findByResolvedFalse(Pageable pageable);

    Page<DeadLetterEvent> findByRejectionReason(RejectionReason reason, Pageable pageable);

    Page<DeadLetterEvent> findBySource(String source, Pageable pageable);

    Page<DeadLetterEvent> findByReceivedAtBetween(OffsetDateTime start, OffsetDateTime end, Pageable pageable);

    List<DeadLetterEvent> findByResolvedFalseAndNextRetryAtBefore(OffsetDateTime now);

    long countByResolvedFalse();
}
