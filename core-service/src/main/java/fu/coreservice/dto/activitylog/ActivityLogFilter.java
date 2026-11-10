package fu.coreservice.dto.activitylog;

import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;

import java.time.LocalDateTime;

public record ActivityLogFilter(int page, int size, LocalDateTime from, LocalDateTime to,
                                String action, ActivityLogStatus status, ActivityActorType actorType,
                                Long actorId) {
}
