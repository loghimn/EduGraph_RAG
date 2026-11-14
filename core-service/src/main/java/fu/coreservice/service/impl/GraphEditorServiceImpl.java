package fu.coreservice.service.impl;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.client.GraphEditorAction;
import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.dto.knowledgegraph.GraphEdgeRequest;
import fu.coreservice.dto.knowledgegraph.GraphMutationResponse;
import fu.coreservice.dto.knowledgegraph.GraphReviewRequest;
import fu.coreservice.dto.knowledgegraph.GraphSnapshotResponse;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.security.CourseAccessPolicy;
import fu.coreservice.service.GraphEditorService;
import fu.coreservice.utils.SecurityUtils;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GraphEditorServiceImpl implements GraphEditorService {
    private final AiCoreClient aiCoreClient;
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;

    @Override
    public GraphSnapshotResponse snapshot(Long courseId) {
        return aiCoreClient.getGraphReview(courseId, ownerId(courseId));
    }

    @Override
    public GraphMutationResponse updateConcept(Long courseId, String conceptId, Long version, ConceptDraftRequest request) {
        return execute(courseId, GraphEditorAction.CONCEPT_UPDATE, conceptId, null, version,
                Map.of("name", request.getName(), "aliases", request.getAliases(), "language", request.getLanguage()));
    }

    @Override
    public GraphMutationResponse confirmConcept(Long courseId, String conceptId, Long version) {
        return execute(courseId, GraphEditorAction.CONCEPT_REVIEW, conceptId, null, version, Map.of());
    }

    @Override
    public GraphMutationResponse createEdge(Long courseId, Long version, GraphEdgeRequest request) {
        return execute(courseId, GraphEditorAction.EDGE_CREATE, null, null, version, edgeBody(request));
    }

    @Override
    public GraphMutationResponse updateEdge(Long courseId, String source, String target, Long version, GraphEdgeRequest request) {
        return execute(courseId, GraphEditorAction.EDGE_UPDATE, source, target, version, edgeBody(request));
    }

    @Override
    public GraphMutationResponse reviewEdge(Long courseId, String source, String target, Long version, GraphReviewRequest request) {
        return execute(courseId, GraphEditorAction.EDGE_REVIEW, source, target, version,
                Map.of("status", request.status(), "note", request.note() == null ? "" : request.note()));
    }

    @Override
    public GraphMutationResponse deleteEdge(Long courseId, String source, String target, Long version) {
        return execute(courseId, GraphEditorAction.EDGE_DELETE, source, target, version, Map.of());
    }

    private Map<String, Object> edgeBody(GraphEdgeRequest request) {
        return Map.of("source_id", request.sourceId(), "target_id", request.targetId(),
                "note", request.note() == null ? "" : request.note());
    }

    private GraphMutationResponse execute(Long courseId, GraphEditorAction action, String source, String target,
                                        Long version, Map<String, Object> body) {
        return aiCoreClient.mutateGraph(courseId, ownerId(courseId), action, source, target, version, body);
    }

    private Long ownerId(Long courseId) {
        User user = userRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        CourseAccessPolicy.requireGraphWriteAccess(course, user);
        return user.getUserId();
    }
}
