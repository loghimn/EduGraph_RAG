package fu.coreservice.service;

import fu.coreservice.entity.MasteryStatus;
import fu.coreservice.entity.PathStepStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface LearnerMasteryService {
    record MasteryView(String conceptId, MasteryStatus status, Boolean selfRated, LocalDateTime updatedAt) {}
    record HistoryView(Long historyId, MasteryStatus oldStatus, MasteryStatus newStatus,
                       String changedBy, LocalDateTime changedAt) {}

    List<MasteryView> list(Long courseId);
    List<String> knownConcepts(Long courseId);
    List<HistoryView> history(Long courseId, String conceptId);
    void initialize(Long courseId, Collection<String> conceptIds, Collection<String> knownIds);
    MasteryView update(Long courseId, String conceptId, MasteryStatus status);
    void updateFromStep(Long courseId, String conceptId, PathStepStatus status);
}
