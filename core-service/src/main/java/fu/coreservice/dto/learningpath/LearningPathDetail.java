package fu.coreservice.dto.learningpath;

import fu.coreservice.entity.PathStepStatus;
import java.time.LocalDateTime;
import java.util.List;

public record LearningPathDetail(
        LearningPathSummary plan, List<String> goalConceptIds, List<Step> steps,
        // Generation warnings are returned on creation only; they are not stored.
        List<String> warnings
) {
    public record Step(Long stepId, String conceptId, String conceptLabel, Integer stepOrder,
                       Integer stageLevel, Boolean outsideDocument, PathStepStatus status,
                       LocalDateTime completedAt, boolean isUnlocked, Integer difficulty, Boolean difficultyRated) {
        public Step(Long stepId, String conceptId, String conceptLabel, Integer stepOrder,
                    Integer stageLevel, Boolean outsideDocument, PathStepStatus status, LocalDateTime completedAt, boolean isUnlocked) {
            this(stepId, conceptId, conceptLabel, stepOrder, stageLevel, outsideDocument, status, completedAt, isUnlocked, null, null);
        }
    }
}
