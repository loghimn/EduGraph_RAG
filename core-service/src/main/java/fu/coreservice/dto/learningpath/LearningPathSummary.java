package fu.coreservice.dto.learningpath;

import fu.coreservice.entity.LearningPathMode;
import fu.coreservice.entity.LearningPathStatus;
import java.time.LocalDateTime;

public record LearningPathSummary(
        Long planId, Long courseId, String planName, LearningPathMode mode,
        LearningPathStatus status, Long documentId, Long graphVersion,
        LocalDateTime generatedAt, LocalDateTime completedAt,
        long totalSteps, long completedSteps, double progressPercentage
) {}
