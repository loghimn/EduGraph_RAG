package fu.coreservice.controller;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.learningpath.LearningPathDetail;
import fu.coreservice.dto.learningpath.LearningPathSummary;
import fu.coreservice.entity.LearningPathMode;
import fu.coreservice.entity.LearningPathStatus;
import fu.coreservice.entity.PathStepStatus;
import fu.coreservice.exception.AiCoreException;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.exception.GlobalExceptionHandler;
import fu.coreservice.service.LearningPathService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LearningPathControllerTest {
    private LearningPathService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        service = mock(LearningPathService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LearningPathController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void createsWithLocationAndTypedEnvelope() throws Exception {
        LearningPathSummary summary = new LearningPathSummary(19L, 7L, "My plan", LearningPathMode.GOAL,
                LearningPathStatus.ACTIVE, null, 3L, null, null, 2, 0, 0);
        when(service.create(eq(7L), any())).thenReturn(
                new LearningPathDetail(summary, List.of("C5"), List.of(), List.of()));
        mvc.perform(post("/api/courses/7/learning-paths").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planName":"My plan","goalConceptIds":["C5"],"expectedGraphVersion":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/learning-paths/19"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.plan.planId").value(19))
                .andExpect(jsonPath("$.data.goalConceptIds[0]").value("C5"));
        verify(service).create(eq(7L), argThat(r -> r.planName().equals("My plan")
                && r.expectedGraphVersion().equals(3L) && r.goalConceptIds().equals(List.of("C5"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"planName\":\" \"}", "{\"documentId\":0}",
            "{\"goalConceptIds\":[\"\"]}", "{\"knownConceptIds\":[null]}",
            "{\"expectedGraphVersion\":-1}"})
    void rejectsInvalidBodiesBeforeService(String body) throws Exception {
        mvc.perform(post("/api/courses/7/learning-paths").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorCode").value("VALIDATION_001"));
        verifyNoInteractions(service);
    }

    @Test
    void bindsPaginationAndStatus() throws Exception {
        when(service.list(7L, LearningPathStatus.ACTIVE, 1, 10)).thenReturn(
                PageResponse.<LearningPathSummary>builder().content(List.of()).page(1).size(10)
                        .totalElements(0).totalPages(0).first(false).last(true).build());
        mvc.perform(get("/api/courses/7/learning-paths").param("status", "ACTIVE")
                        .param("page", "1").param("size", "10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(1));
        verify(service).list(7L, LearningPathStatus.ACTIVE, 1, 10);
    }

    @Test
    void rejectsUnknownStatus() throws Exception {
        mvc.perform(get("/api/courses/7/learning-paths").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void hidesOtherLearnersPlan() throws Exception {
        when(service.get(19L)).thenThrow(new AppException(ErrorCode.LEARNING_PATH_NOT_FOUND));
        mvc.perform(get("/api/learning-paths/19"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("LEARNING_PATH_001"));
    }

    @Test
    void relaysAiConflictsAndPayload() throws Exception {
        when(service.create(eq(7L), any())).thenThrow(new AiCoreException(409, "Cycle detected",
                Map.of("conflicts", List.of(List.of("C1->C2", "C2->C1")))));
        mvc.perform(post("/api/courses/7/learning-paths").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AI_CORE_409"))
                .andExpect(jsonPath("$.data.conflicts[0][0]").value("C1->C2"));
    }

    @Test
    void reportsStalePreviewAsConflict() throws Exception {
        when(service.create(eq(7L), any())).thenThrow(new AppException(ErrorCode.LEARNING_PATH_GRAPH_CHANGED));
        mvc.perform(post("/api/courses/7/learning-paths").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LEARNING_PATH_003"));
    }

    @Test
    void patchesStatusAndSerializesUnlockFlag() throws Exception {
        var step = new LearningPathDetail.Step(40L, "C1", "Concept", 1, 0,
                null, PathStepStatus.COMPLETED, null, true);
        when(service.updateStep(eq(19L), eq(40L), any())).thenReturn(
                new LearningPathDetail(null, List.of(), List.of(step), List.of()));
        mvc.perform(patch("/api/learning-paths/19/steps/40").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.steps[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.steps[0].isUnlocked").value(true));
        verify(service).updateStep(eq(19L), eq(40L), argThat(r -> r.status() == PathStepStatus.COMPLETED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}", "{\"status\":\"UNKNOWN\"}"})
    void rejectsMissingOrUnknownStepStatus(String body) throws Exception {
        mvc.perform(patch("/api/learning-paths/19/steps/40").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void lockedStepReturnsConflict() throws Exception {
        when(service.updateStep(eq(19L), eq(40L), any()))
                .thenThrow(new AppException(ErrorCode.LEARNING_PATH_STEP_LOCKED));
        mvc.perform(patch("/api/learning-paths/19/steps/40").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LEARNING_PATH_005"));
    }
}
