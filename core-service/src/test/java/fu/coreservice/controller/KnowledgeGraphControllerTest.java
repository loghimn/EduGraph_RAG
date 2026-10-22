package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.knowledgegraph.ConceptDraftRequest;
import fu.coreservice.exception.GlobalExceptionHandler;
import fu.coreservice.service.AiRagService;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private AiRagService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(AiRagService.class);
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeGraphController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void draftBindsTypedFieldsAndDefaults() throws Exception {
        when(service.createConceptDraft(eq(23L), any())).thenReturn(ResponseEntity.ok(
                ApiResponse.<Map<String, Object>>builder().success(true).data(Map.of("draft_id", "draft-1")).build()));
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
        verifyNoInteractions(service);
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
