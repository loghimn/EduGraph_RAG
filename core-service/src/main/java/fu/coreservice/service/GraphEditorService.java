package fu.coreservice.service;

import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.dto.knowledgegraph.GraphEdgeRequest;
import fu.coreservice.dto.knowledgegraph.GraphReviewRequest;
import fu.coreservice.dto.knowledgegraph.GraphSnapshotResponse;
import fu.coreservice.dto.knowledgegraph.GraphMutationResponse;

/** Typed graph curation use cases, independent of HTTP transport. */
public interface GraphEditorService {
    GraphSnapshotResponse snapshot(Long courseId);
    GraphMutationResponse updateConcept(Long courseId, String conceptId, Long version, ConceptDraftRequest request);
    GraphMutationResponse confirmConcept(Long courseId, String conceptId, Long version);
    GraphMutationResponse createEdge(Long courseId, Long version, GraphEdgeRequest request);
    GraphMutationResponse updateEdge(Long courseId, String source, String target, Long version, GraphEdgeRequest request);
    GraphMutationResponse reviewEdge(Long courseId, String source, String target, Long version, GraphReviewRequest request);
    GraphMutationResponse deleteEdge(Long courseId, String source, String target, Long version);
}
