package fu.coreservice.dto.activitylog;

import fu.coreservice.entity.ActivityActorType;
import fu.coreservice.entity.ActivityLogStatus;

import java.util.Objects;

/** Internal command. Callers must provide safe summaries, never credentials or raw payloads. */
public record ActivityLogRequest(Long actorId, ActivityActorType actorType, String action,
                                 ActivityLogStatus status, String resourceType, String errorCode,
                                 String message, Long durationMs) {
    public ActivityLogRequest {
        Objects.requireNonNull(actorType, "actorType is required");
        Objects.requireNonNull(status, "status is required");
        if (actorId != null && actorId <= 0) {
            throw new IllegalArgumentException("actorId must be positive");
        }
        action = requiredToken(action, "action", 100);
        resourceType = requiredToken(resourceType, "resourceType", 50);
        if (errorCode != null) {
            errorCode = errorCode.trim();
            if (errorCode.isEmpty()) errorCode = null;
            else if (errorCode.length() > 100) {
                throw new IllegalArgumentException("errorCode must be at most 100 characters");
            }
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
    }

    private static String requiredToken(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        value = value.trim();
        if (value.length() > maxLength || !value.matches("[A-Z][A-Z0-9_]*")) {
            throw new IllegalArgumentException(field + " must be an uppercase identifier of at most "
                    + maxLength + " characters");
        }
        return value;
    }
}
