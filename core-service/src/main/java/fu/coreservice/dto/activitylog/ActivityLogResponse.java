package fu.coreservice.dto.activitylog;

import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;

import java.time.LocalDateTime;

public record ActivityLogResponse(Long logId, LocalDateTime createdAt, Long actorId, String actorUsername,
                                  ActivityActorType actorType, String action, String resourceType,
                                  ActivityLogStatus status, String errorCode, String message, Long durationMs) {
}
