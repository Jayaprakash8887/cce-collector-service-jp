package org.openphc.cce.collector.domain.repository;

import org.openphc.cce.collector.domain.model.EventLog;
import org.openphc.cce.collector.domain.model.enums.PublishStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EventLogRepository extends JpaRepository<EventLog, UUID> {

    Optional<EventLog> findByCloudeventsIdAndSource(String cloudeventsId, String source);

    Optional<EventLog> findBySourceAndSourceEventId(String source, String sourceEventId);

    List<EventLog> findByPublishStatusAndReceivedAtBefore(PublishStatus publishStatus, OffsetDateTime cutoff);

    long countByPublishStatus(PublishStatus publishStatus);
}
