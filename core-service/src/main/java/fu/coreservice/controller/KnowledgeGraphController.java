package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.dto.knowledgegraph.GraphEdgeRequest;
import fu.coreservice.dto.knowledgegraph.GraphMutationResponse;
import fu.coreservice.dto.knowledgegraph.GraphReviewRequest;
import fu.coreservice.dto.knowledgegraph.GraphSnapshotResponse;
import fu.coreservice.service.AiRagService;
import fu.coreservice.service.GraphEditorService;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/courses/{courseId}/knowledge-graph")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final AiRagService aiRagService;
    private final GraphEditorService graphEditorService;

    @GetMapping("/review")
    public ResponseEntity<ApiResponse<GraphSnapshotResponse>> reviewGraph(@PathVariable Long courseId) {
        return ResponseEntity.ok(ApiResponse.<GraphSnapshotResponse>builder()
                .success(true).message("Graph operation completed").data(graphEditorService.snapshot(courseId)).build());
    }

    @PutMapping("/concepts/{conceptId}")
    public ResponseEntity<ApiResponse<GraphMutationResponse>> updateConcept(@PathVariable Long courseId,
            @PathVariable String conceptId, @Valid @RequestBody ConceptDraftRequest request,
            @RequestParam("expected_version") Long version) {
        return ResponseEntity.ok(ApiResponse.<GraphMutationResponse>builder()
                .success(true).message("Graph operation completed").data(graphEditorService.updateConcept(courseId, conceptId, version, request)).build());
    }

    @PostMapping("/concepts/{conceptId}/review")
    public ResponseEntity<ApiResponse<GraphMutationResponse>> confirmConcept(@PathVariable Long courseId,
            @PathVariable String conceptId, @RequestParam("expected_version") Long version) {
        return ResponseEntity.ok(ApiResponse.<GraphMutationResponse>builder()
                .success(true).message("Graph operation completed").data(graphEditorService.confirmConcept(courseId, conceptId, version)).build());
    }

    @PostMapping("/edges")
    public ResponseEntity<ApiResponse<GraphMutationResponse>> createEdge(@PathVariable Long courseId,
            @Valid @RequestBody GraphEdgeRequest request,
            @RequestParam("expected_version") Long version) {
        return ResponseEntity.ok(ApiResponse.<GraphMutationResponse>builder()
                .success(true).message("Graph operation completed").data(graphEditorService.createEdge(courseId, version, request)).build());
    }

    @PutMapping("/edges/{sourceId}/{targetId}")
    public ResponseEntity<ApiResponse<GraphMutationResponse>> updateEdge(@PathVariable Long courseId,
            @PathVariable String sourceId, @PathVariable String targetId,
            @Valid @RequestBody GraphEdgeRequest request,
            @RequestParam("expected_version") Long version) {
        return ResponseEntity.ok(ApiResponse.<GraphMutationResponse>builder()
                .success(true).message("Graph operation completed").data(graphEditorService.updateEdge(courseId, sourceId, targetId, version, request)).build());
    }

    @PostMapping("/edges/{sourceId}/{targetId}/review")
    public ResponseEntity<ApiResponse<GraphMutationResponse>> reviewEdge(@PathVariable Long courseId,
            @PathVariable String sourceId, @PathVariable String targetId,
            @Valid @RequestBody GraphReviewRequest request,
            @RequestParam("expected_version") Long version) {
        return ResponseEntity.ok(ApiResponse.<GraphMutationResponse>builder()
                .success(true).message("Graph operation completed").data(graphEditorService.reviewEdge(courseId, sourceId, targetId, version, request)).build());
    }

    @DeleteMapping("/edges/{sourceId}/{targetId}")
    public ResponseEntity<ApiResponse<GraphMutationResponse>> deleteEdge(@PathVariable Long courseId,
            @PathVariable String sourceId, @PathVariable String targetId,
            @RequestParam("expected_version") Long version) {
        return ResponseEntity.ok(ApiResponse.<GraphMutationResponse>builder()
                .success(true).message("Graph operation completed").data(graphEditorService.deleteEdge(courseId, sourceId, targetId, version)).build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getKnowledgeGraph(
            @PathVariable Long courseId) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Knowledge graph retrieved successfully").data(aiRagService.getKnowledgeGraph(courseId)).build());
    }

    @GetMapping("/concepts/{conceptId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConceptDetail(
            @PathVariable Long courseId,
            @PathVariable String conceptId) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Knowledge graph concept retrieved successfully").data(aiRagService.getKnowledgeGraphConcept(courseId, conceptId)).build());
    }

    @PostMapping("/concept-drafts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> computeConceptDraft(
            @PathVariable Long courseId, @Valid @RequestBody ConceptDraftRequest request) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Concept embedding ready").data(aiRagService.createConceptDraft(courseId, request)).build());
    }

    @PostMapping("/concept-drafts/{draftId}/publish")
    public ResponseEntity<ApiResponse<Map<String, Object>>> publishConceptDraft(
            @PathVariable Long courseId, @PathVariable String draftId) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Concept added to graph").data(aiRagService.publishConceptDraft(courseId, draftId)).build());
    }

    @GetMapping("/archived-concepts")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getArchivedConcepts(@PathVariable Long courseId) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Concept operation completed").data(aiRagService.getArchivedConcepts(courseId)).build());
    }

    @GetMapping("/concepts/{conceptId}/deletion-impact")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDeletionImpact(
            @PathVariable Long courseId, @PathVariable String conceptId) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Concept operation completed").data(aiRagService.getConceptDeletionImpact(courseId, conceptId)).build());
    }

    @DeleteMapping("/concepts/{conceptId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> archiveConcept(
            @PathVariable Long courseId, @PathVariable String conceptId,
            @RequestParam("expected_version") Long expectedVersion) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Concept operation completed").data(aiRagService.archiveConcept(courseId, conceptId, expectedVersion)).build());
    }

    @PostMapping("/concepts/{conceptId}/restore")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restoreConcept(
            @PathVariable Long courseId, @PathVariable String conceptId,
            @RequestParam("expected_version") Long expectedVersion) {
        return ResponseEntity.ok(ApiResponse.<Map<String, Object>>builder()
                .success(true).message("Concept operation completed").data(aiRagService.restoreConcept(courseId, conceptId, expectedVersion)).build());
    }
}
