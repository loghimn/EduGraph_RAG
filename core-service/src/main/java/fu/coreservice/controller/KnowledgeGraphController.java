package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.service.AiRagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/courses/{courseId}/knowledge-graph")
@RequiredArgsConstructor
public class KnowledgeGraphController {

    private final AiRagService aiRagService;

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
}
