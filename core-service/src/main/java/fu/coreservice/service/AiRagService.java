package fu.coreservice.service;

import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.dto.learningpath.LearningPathPlanRequest;
import java.util.Map;

public interface AiRagService {

    Map<String, Object> getKnowledgeGraph(Long courseId);

    Map<String, Object> getKnowledgeGraphConcept(Long courseId, String conceptId);

    Map<String, Object> planLearningPath(Long courseId, LearningPathPlanRequest request);

    Map<String, Object> createConceptDraft(Long courseId, ConceptDraftRequest request);

    Map<String, Object> publishConceptDraft(Long courseId, String draftId);

    Map<String, Object> getConceptDeletionImpact(Long courseId, String conceptId);

    Map<String, Object> getArchivedConcepts(Long courseId);

    Map<String, Object> archiveConcept(Long courseId, String conceptId, Long expectedVersion);

    Map<String, Object> restoreConcept(Long courseId, String conceptId, Long expectedVersion);
}
