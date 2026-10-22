package fu.coreservice.service;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.learningpath.LearningPathPlanRequest;
import org.springframework.http.ResponseEntity;
import java.util.Map;

public interface AiRagService {

    ResponseEntity<ApiResponse<Map<String, Object>>> getKnowledgeGraph(Long courseId);

    ResponseEntity<ApiResponse<Map<String, Object>>> getKnowledgeGraphConcept(Long courseId, String conceptId);

    ResponseEntity<ApiResponse<Map<String, Object>>> planLearningPath(Long courseId, LearningPathPlanRequest request);

    ResponseEntity<ApiResponse<Map<String, Object>>> createConceptDraft(Long courseId, Map<String, Object> request);

    ResponseEntity<ApiResponse<Map<String, Object>>> publishConceptDraft(Long courseId, String draftId);
}
