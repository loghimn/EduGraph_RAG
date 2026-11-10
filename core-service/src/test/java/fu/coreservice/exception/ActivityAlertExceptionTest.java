package fu.coreservice.exception;

import fu.coreservice.repository.*;
import fu.coreservice.service.ActivityAlertService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

    private void bindSession(Long id) {
        var request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("sessionId", id.toString()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
