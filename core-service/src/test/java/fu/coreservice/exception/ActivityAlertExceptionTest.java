package fu.coreservice.exception;

import fu.coreservice.repository.*;
import fu.coreservice.service.ActivityAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityAlertExceptionTest {
    private final ActivityAlertService alerts = mock(ActivityAlertService.class);
    private final ChatSessionRepository sessions = mock(ChatSessionRepository.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(alerts, sessions,
            mock(LearningPathPlanRepository.class), mock(ChatMessageRepository.class));

    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void ordinaryValidationAndMissingResourceDoNotAlert() {
        handler.handleAppException(new AppException(ErrorCode.INVALID_REQUEST));
        bindSession(99L);
        handler.handleAppException(new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        verifyNoInteractions(alerts);
    }

    @Test void existingPrivateResourceRejectionAlertsAndPreserves404() {
        bindSession(99L);
        when(sessions.existsById(99L)).thenReturn(true);
        var response = handler.handleAppException(new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(alerts).accessDenied("CHAT_SESSION");
    }

    @Test void authorizationFailureAlertsButNormalAiClientErrorDoesNot() {
        handler.handleAppException(new AppException(ErrorCode.COURSE_ACCESS_DENIED));
        handler.handleAiCoreException(new AiCoreException(422, "invalid input", Map.of()));
        verify(alerts).accessDenied("COURSE");
        verifyNoMoreInteractions(alerts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Invalid Internal Key", "Forbidden"})
    void internalOrUnconfirmed403IsSystemFailureAndPreservesResponse(String detail) {
        var error = new AiCoreException(403, detail, Map.of("detail", detail));
        var response = handler.handleAiCoreException(error);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().getMessage()).isEqualTo(detail);
        assertThat(response.getBody().getData()).isEqualTo(error.getBody());
        verify(alerts).repeatedSystemFailure("AI_CORE", "SYSTEM");
        verifyNoMoreInteractions(alerts);
    }

    @Test void confirmedOwnerDenialRemainsUserAccessAlert() {
        handler.handleAiCoreException(new AiCoreException(403,
                "Permission denied: user is not course owner", Map.of()));
        verify(alerts).accessDenied("KNOWLEDGE_GRAPH");
        verifyNoMoreInteractions(alerts);
    }

    @Test void upstreamServerFailureRemainsSystemFailure() {
        handler.handleAiCoreException(new AiCoreException(503, "Unavailable", Map.of()));
        verify(alerts).repeatedSystemFailure("AI_CORE", "SYSTEM");
        verifyNoMoreInteractions(alerts);
    }

    private void bindSession(Long id) {
        var request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("sessionId", id.toString()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
