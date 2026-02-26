package org.openphc.cce.collector.domain.repository;

import org.openphc.cce.collector.domain.model.InboundEvent;
import org.openphc.cce.collector.domain.model.enums.InboundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InboundEventRepository extends JpaRepository<InboundEvent, UUID> {

    Optional<InboundEvent> findByCloudeventsIdAndSource(String cloudeventsId, String source);

    boolean existsByCloudeventsIdAndSource(String cloudeventsId, String source);

    long countByStatus(InboundStatus status);
}
