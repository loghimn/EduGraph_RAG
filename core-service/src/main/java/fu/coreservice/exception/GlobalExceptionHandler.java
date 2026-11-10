package fu.coreservice.exception;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.service.ActivityAlertService;
import fu.coreservice.repository.ChatSessionRepository;
import fu.coreservice.repository.LearningPathPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {
    private final ActivityAlertService alerts;
    private final ChatSessionRepository sessions;
    private final LearningPathPlanRepository plans;
    private final fu.coreservice.repository.ChatMessageRepository messages;

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<ApiResponse<Void>> handleAsyncTimeout(AsyncRequestTimeoutException ex,
                                                               HttpServletResponse response) {
        // A committed SSE response cannot be replaced with a JSON error document.
        if (response.isCommitted()) return null;
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.<Void>builder().success(false).errorCode("CHAT_STREAM_TIMEOUT")
                        .message("Chat stream exceeded its time limit").build());
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncResponseUnavailable(AsyncRequestNotUsableException ex) {
        // Container has already completed/closed the async response; do not write again.
        log.debug("Async response is no longer writable: {}", ex.getMessage());
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleEndpointNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON).body(
                ApiResponse.<Void>builder().success(false).errorCode("SYSTEM_404")
                        .message("Endpoint not found").build());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        ErrorCode errorCode = ErrorCode.INVALID_CREDENTIALS;

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
                );
    }

    @ExceptionHandler(DisabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleDisabled(DisabledException ex) {
        ErrorCode errorCode = ErrorCode.ACCOUNT_DISABLED;

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
                );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();

        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;

        return ResponseEntity.status(errorCode.getHttpStatus()).contentType(MediaType.APPLICATION_JSON).body(
                ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .data(fieldErrors)
                        .build()
                );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException ex) {
        ErrorCode errorCode = ErrorCode.FILE_TOO_LARGE;

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        alerts.accessDenied("SYSTEM");
        ErrorCode errorCode = ErrorCode.ACCESS_DENIED;

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
                );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {

        ErrorCode errorCode = ErrorCode.INVALID_REQUEST;

        return ResponseEntity.status(errorCode.getHttpStatus()).contentType(MediaType.APPLICATION_JSON).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {

        ErrorCode errorCode = ErrorCode.INVALID_PARAMETER;

        return ResponseEntity.status(errorCode.getHttpStatus()).contentType(MediaType.APPLICATION_JSON).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode("SYSTEM_405")
                        .message("Method not allowed")
                        .build()
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingRequestParameter(MissingServletRequestParameterException ex) {

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode("SYSTEM_400")
                        .message("Required request parameter is missing")
                        .build()
        );
    }


    @ExceptionHandler(AiCoreException.class)
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleAiCoreException(AiCoreException ex) {
        if (ex.getHttpStatus() == 403) alerts.accessDenied("KNOWLEDGE_GRAPH");
        else if (ex.getHttpStatus() >= 500) alerts.repeatedSystemFailure("AI_CORE", "SYSTEM");
        log.warn("AI core call failed. status={}, detail={}", ex.getHttpStatus(), ex.getDetail());

        HttpStatus status = HttpStatus.resolve(ex.getHttpStatus());
        if (status == null || status.is2xxSuccessful()) {
            status = HttpStatus.BAD_GATEWAY;
        }

        return ResponseEntity.status(status).body(
                ApiResponse.<Map<String, Object>>builder()
                        .success(false)
                        .errorCode("AI_CORE_" + ex.getHttpStatus())
                        .message(ex.getDetail())
                        .data(ex.getBody().isEmpty() ? null : ex.getBody())
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
        alerts.critical("UNEXPECTED_SYSTEM_ERROR", "SYSTEM", "INTERNAL_SERVER_ERROR");
        log.error("Unexpected exception occurred", ex);
        ErrorCode errorCode = ErrorCode.INTERNAL_SERVER_ERROR;

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
                );
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiResponse<Void>> handleAppException(AppException ex) {
        ErrorCode errorCode = ex.getErrorCode();
        if (errorCode == ErrorCode.ACCESS_DENIED || errorCode == ErrorCode.COURSE_ACCESS_DENIED) {
            alerts.accessDenied("COURSE");
        } else if (errorCode == ErrorCode.INTERNAL_SERVER_ERROR) {
            alerts.critical("UNEXPECTED_SYSTEM_ERROR", "SYSTEM", errorCode.name());
        } else if (errorCode.getHttpStatus().is5xxServerError()) {
            alerts.repeatedSystemFailure(errorCode.name(), "SYSTEM");
        } else if (errorCode == ErrorCode.CHAT_SESSION_NOT_FOUND || errorCode == ErrorCode.LEARNING_PATH_NOT_FOUND
                || errorCode == ErrorCode.CHAT_MESSAGE_NOT_FOUND) {
            observeMaskedRejection(errorCode);
        }

        return ResponseEntity.status(errorCode.getHttpStatus()).contentType(MediaType.APPLICATION_JSON).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
        );
    }
    // Preserve 404 responses while recognizing access to existing, inaccessible private resources.
    private void observeMaskedRejection(ErrorCode code) {
        try {
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet)) return;
            Object variables = servlet.getRequest().getAttribute(
                    org.springframework.web.servlet.HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
            if (!(variables instanceof Map<?, ?> ids)) return;
            String key = code == ErrorCode.CHAT_SESSION_NOT_FOUND ? "sessionId"
                    : code == ErrorCode.CHAT_MESSAGE_NOT_FOUND ? "messageId" : "planId";
            Object id = ids.get(key);
            if (id == null) return;
            long value = Long.parseLong(id.toString());
            if (code == ErrorCode.CHAT_MESSAGE_NOT_FOUND && messages.existsById(value)) alerts.accessDenied("CHAT_MESSAGE");
            if (code == ErrorCode.CHAT_SESSION_NOT_FOUND && sessions.existsById(value)) alerts.accessDenied("CHAT_SESSION");
            if (code == ErrorCode.LEARNING_PATH_NOT_FOUND && plans.existsById(value)) alerts.accessDenied("LEARNING_PATH");
        } catch (RuntimeException ignored) {
            // Audit enrichment must never change the original response.
        }
    }

}
