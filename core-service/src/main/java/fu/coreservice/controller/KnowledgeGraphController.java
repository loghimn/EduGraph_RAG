package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.dto.knowledgegraph.GraphEdgeRequest;
import fu.coreservice.dto.knowledgegraph.GraphReviewRequest;
import org.springframework.web.bind.annotation.PutMapping;
import jakarta.validation.Valid;
import fu.coreservice.service.AiRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/courses/{courseId}/knowledge-graph")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final AiRagService aiRagService;

    @GetMapping("/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewGraph(@PathVariable Long courseId) {
        return aiRagService.editGraph(courseId, "snapshot", null, null, null, Map.of());
    }

    @PutMapping("/concepts/{conceptId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateConcept(@PathVariable Long courseId,
            @PathVariable String conceptId, @Valid @RequestBody ConceptDraftRequest request,
            @RequestParam("expected_version") Long version) {
        return aiRagService.editGraph(courseId, "concept-update", conceptId, null, version,
                Map.of("name", request.getName(), "aliases", request.getAliases(), "language", request.getLanguage()));
    }

    @PostMapping("/concepts/{conceptId}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmConcept(@PathVariable Long courseId,
            @PathVariable String conceptId, @RequestParam("expected_version") Long version) {
        return aiRagService.editGraph(courseId, "concept-review", conceptId, null, version, Map.of());
    }

    @PostMapping("/edges")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createEdge(@PathVariable Long courseId,
            @Valid @RequestBody GraphEdgeRequest request,
            @RequestParam("expected_version") Long version) {
        return aiRagService.editGraph(courseId, "edge-create", null, null, version, edgeBody(request));
    }

    @PutMapping("/edges/{sourceId}/{targetId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateEdge(@PathVariable Long courseId,
            @PathVariable String sourceId, @PathVariable String targetId,
            @Valid @RequestBody GraphEdgeRequest request,
            @RequestParam("expected_version") Long version) {
        return aiRagService.editGraph(courseId, "edge-update", sourceId, targetId, version, edgeBody(request));
    }

    @PostMapping("/edges/{sourceId}/{targetId}/review")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reviewEdge(@PathVariable Long courseId,
            @PathVariable String sourceId, @PathVariable String targetId,
            @Valid @RequestBody GraphReviewRequest request,
            @RequestParam("expected_version") Long version) {
        return aiRagService.editGraph(courseId, "edge-review", sourceId, targetId, version,
                Map.of("status", request.status(), "note", request.note() == null ? "" : request.note()));
    }

    @DeleteMapping("/edges/{sourceId}/{targetId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteEdge(@PathVariable Long courseId,
            @PathVariable String sourceId, @PathVariable String targetId,
            @RequestParam("expected_version") Long version) {
        return aiRagService.editGraph(courseId, "edge-delete", sourceId, targetId, version, Map.of());
    }

    private Map<String, Object> edgeBody(GraphEdgeRequest request) {
        return Map.of("source_id", request.sourceId(), "target_id", request.targetId(),
                "note", request.note() == null ? "" : request.note());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKnowledgeGraph(
            @PathVariable Long courseId) {
        return aiRagService.getKnowledgeGraph(courseId);
    }

    @GetMapping("/concepts/{conceptId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConceptDetail(
            @PathVariable Long courseId,
            @PathVariable String conceptId) {
        return aiRagService.getKnowledgeGraphConcept(courseId, conceptId);
    }

    @PostMapping("/concept-drafts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> computeConceptDraft(
            @PathVariable Long courseId, @Valid @RequestBody ConceptDraftRequest request) {
        return aiRagService.createConceptDraft(courseId, request);
    }

    @PostMapping("/concept-drafts/{draftId}/publish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishConceptDraft(
            @PathVariable Long courseId, @PathVariable String draftId) {
        return aiRagService.publishConceptDraft(courseId, draftId);
    }

    @GetMapping("/archived-concepts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getArchivedConcepts(@PathVariable Long courseId) {
        return aiRagService.getArchivedConcepts(courseId);
    }

    @GetMapping("/concepts/{conceptId}/deletion-impact")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDeletionImpact(
            @PathVariable Long courseId, @PathVariable String conceptId) {
        return aiRagService.getConceptDeletionImpact(courseId, conceptId);
    }

    @DeleteMapping("/concepts/{conceptId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> archiveConcept(
            @PathVariable Long courseId, @PathVariable String conceptId,
            @RequestParam("expected_version") Long expectedVersion) {
        return aiRagService.archiveConcept(courseId, conceptId, expectedVersion);
    }

    @PostMapping("/concepts/{conceptId}/restore")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restoreConcept(
            @PathVariable Long courseId, @PathVariable String conceptId,
            @RequestParam("expected_version") Long expectedVersion) {
        return aiRagService.restoreConcept(courseId, conceptId, expectedVersion);
    }
}
