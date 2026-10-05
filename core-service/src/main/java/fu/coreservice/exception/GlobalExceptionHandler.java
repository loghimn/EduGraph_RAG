package fu.coreservice.exception;

import fu.coreservice.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

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

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
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

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
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

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex) {
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

        return ResponseEntity.status(errorCode.getHttpStatus()).body(
                ApiResponse.<Void>builder()
                        .success(false)
                        .errorCode(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
        );
    }
}
