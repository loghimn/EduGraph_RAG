package fu.coreservice.controller;

import fu.coreservice.dto.knowledgegraph.GraphMutationResponse;
import fu.coreservice.exception.AiCoreException;
import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.exception.GlobalExceptionHandler;
import fu.coreservice.service.AiRagService;
import fu.coreservice.service.GraphEditorService;
import fu.coreservice.dto.knowledgegraph.GraphEdgeRequest;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KnowledgeGraphControllerTest {
    @Test
    void edgeRejectsInvalidReviewStatusBeforeService() throws Exception {
        mvc.perform(post("/api/courses/23/knowledge-graph/edges/a/b/review?expected_version=8")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"AUTO\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, editor);
    }

    @Test
    void edgeCreateForwardsOnlyTypedFields() throws Exception {
        when(editor.createEdge(eq(23L), eq(8L), any())).thenReturn(new GraphMutationResponse(9L, null, "a", "b", "MANUAL"));
        mvc.perform(post("/api/courses/23/knowledge-graph/edges?expected_version=8")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source_id\":\"a\",\"target_id\":\"b\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.graph_version").value(9))
                .andExpect(jsonPath("$.data.source").value("a"))
                .andExpect(jsonPath("$.data.id").doesNotExist());
        verify(editor).createEdge(23L, 8L, new GraphEdgeRequest("a", "b", null));
    }

    @Test
    void graphConflictRetainsAiStatusAndPayloadThroughTheExceptionHandler() throws Exception {
        when(editor.createEdge(eq(23L), eq(8L), any())).thenThrow(new AiCoreException(409,
                "Graph changed; reload before saving", Map.of("graph_version", 9, "cycle", List.of("a", "b"))));
        mvc.perform(post("/api/courses/23/knowledge-graph/edges?expected_version=8")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"source_id\":\"a\",\"target_id\":\"b\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AI_CORE_409"))
                .andExpect(jsonPath("$.data.graph_version").value(9))
                .andExpect(jsonPath("$.data.cycle[0]").value("a"));
    }

    private AiRagService service;
    private GraphEditorService editor;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(AiRagService.class);
        editor = mock(GraphEditorService.class);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeGraphController(service, editor))
                .setControllerAdvice(new GlobalExceptionHandler(org.mockito.Mockito.mock(fu.coreservice.service.ActivityAlertService.class), org.mockito.Mockito.mock(fu.coreservice.repository.ChatSessionRepository.class), org.mockito.Mockito.mock(fu.coreservice.repository.LearningPathPlanRepository.class), org.mockito.Mockito.mock(fu.coreservice.repository.ChatMessageRepository.class))).build();
    }

    @Test
    void draftBindsTypedFieldsAndDefaults() throws Exception {
        when(service.createConceptDraft(eq(23L), any())).thenReturn(Map.of("draft_id", "draft-1"));
        mvc.perform(post("/api/courses/23/knowledge-graph/concept-drafts")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"HTTP Requests\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.draft_id").value("draft-1"));
        verify(service).createConceptDraft(eq(23L), argThat(r -> r.getName().equals("HTTP Requests")
                && r.getAliases().isEmpty() && r.getLanguage().equals("mixed")));
    }

    @Test
    void draftAcceptsSwaggerExample() throws Exception {
        mvc.perform(post("/api/courses/23/knowledge-graph/concept-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HTTP Requests\",\"aliases\":[\"HTTP Request\"],\"language\":\"en\"}"))
                .andExpect(status().isOk());
        verify(service).createConceptDraft(eq(23L), argThat(r -> r.getAliases().equals(List.of("HTTP Request"))
                && r.getLanguage().equals("en")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"name\":\" \"}", "{\"name\":\"A\",\"language\":\"xx\"}",
            "{\"name\":\"A\",\"aliases\":[\"\"]}", "{\"name\":\"A\",\"aliases\":null}"})
    void invalidDraftIsRejectedBeforeService(String body) throws Exception {
        mvc.perform(post("/api/courses/23/knowledge-graph/concept-drafts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, editor);
    }

    @Test
    void swaggerSchemaExposesConceptFieldsInsteadOfMap() {
        var resolved = ModelConverters.getInstance().resolveAsResolvedSchema(
                new AnnotatedType(ConceptDraftRequest.class));
        var schema = resolved.referencedSchemas.get("ConceptDraftRequest");
        assertThat(schema.getProperties().keySet()).containsExactlyInAnyOrder("name", "aliases", "language");
        assertThat(schema.getRequired()).contains("name");
        assertThat(schema.getExample().toString()).contains("HTTP Requests");
    }
}
