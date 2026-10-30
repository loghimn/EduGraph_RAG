package fu.coreservice.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    // Authentication / Authorization
    UNAUTHORIZED("AUTH_001", "Authentication required", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("AUTH_002", "Access denied", HttpStatus.FORBIDDEN),
    INVALID_CREDENTIALS("AUTH_003", "Invalid email or password", HttpStatus.UNAUTHORIZED),
    ACCOUNT_DISABLED("AUTH_004", "Account is deactivated", HttpStatus.UNAUTHORIZED),
    INVALID_REFRESH_TOKEN("AUTH_005", "Invalid refresh token", HttpStatus.UNAUTHORIZED),
    REFRESH_TOKEN_EXPIRED("AUTH_006", "Refresh token expired", HttpStatus.UNAUTHORIZED),

    INVALID_RESET_OTP("AUTH_007", "OTP is invalid, expired or has exceeded the attempt limit", HttpStatus.BAD_REQUEST),
    CURRENT_PASSWORD_INCORRECT("AUTH_008", "Current password is incorrect", HttpStatus.BAD_REQUEST),

    // Validation
    VALIDATION_FAILED("VALIDATION_001", "Validation failed", HttpStatus.BAD_REQUEST),

    // User
    EMAIL_ALREADY_EXISTS("USER_001", "Email already exists", HttpStatus.CONFLICT),
    USERNAME_ALREADY_EXISTS("USER_002", "Username already exists", HttpStatus.CONFLICT),
    USER_NOT_FOUND("USER_003", "User not found", HttpStatus.NOT_FOUND),

    // Course Enrollment
    ALREADY_ENROLLED_COURSE("COURSE_ENROLL_001", "You are already enrolled in this course", HttpStatus.CONFLICT),
    NOT_ENROLLED_COURSE("COURSE_ENROLL_002", "You are not enrolled in this course", HttpStatus.NOT_FOUND),

    // Course
    COURSE_NOT_FOUND("COURSE_001", "Course not found", HttpStatus.NOT_FOUND),
    COURSE_ACCESS_DENIED("COURSE_002", "Course not found or you are not the creator", HttpStatus.NOT_FOUND),
    PUBLIC_COURSE_NOT_FOUND("COURSE_003", "Public course not found", HttpStatus.NOT_FOUND),

    // Document
    DOCUMENT_NOT_FOUND("DOCUMENT_001", "Document not found", HttpStatus.NOT_FOUND),
    FILE_EMPTY("DOCUMENT_002", "File is null or empty", HttpStatus.BAD_REQUEST),
    FILE_UPLOAD_FAILED("DOCUMENT_003", "Failed to upload file", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_READ_FAILED("DOCUMENT_004", "Failed to read file content", HttpStatus.INTERNAL_SERVER_ERROR),
    SIGNED_URL_FAILED("DOCUMENT_005", "Failed to create signed URL", HttpStatus.INTERNAL_SERVER_ERROR),
    FILE_TOO_LARGE("DOCUMENT_006", "File size must not exceed 40 MB", HttpStatus.CONTENT_TOO_LARGE),
    INVALID_FILE_TYPE("DOCUMENT_007", "Invalid !!! Only PDF, DOCX, PPTX and TXT files", HttpStatus.BAD_REQUEST),
    FILE_NAME_EMPTY("DOCUMENT_008", "File name must not be empty", HttpStatus.BAD_REQUEST),
    DOCUMENT_ALREADY_PROCESSING("DOCUMENT_009", "Document is currently being processed", HttpStatus.CONFLICT),
    DOCUMENT_PAUSED("DOCUMENT_010", "Document processing is paused", HttpStatus.CONFLICT),
    DOCUMENT_CANCELLED("DOCUMENT_011", "Document has been cancelled", HttpStatus.CONFLICT),
    RETRY_NOT_ALLOWED("DOCUMENT_012", "Retry is only allowed for documents in FAILED or PAUSED state", HttpStatus.CONFLICT),
    CANCEL_NOT_ALLOWED("DOCUMENT_013", "Only documents in PENDING, PROCESSING, PAUSED, or FAILED state can be cancelled", HttpStatus.CONFLICT),
    PAUSE_NOT_ALLOWED("DOCUMENT_014", "Only documents in PROCESSING state can be paused", HttpStatus.CONFLICT),
    USER_ERROR_NO_RETRY("DOCUMENT_015", "This error type cannot be retried (user-side issue)", HttpStatus.UNPROCESSABLE_ENTITY),

    // Common
    INVALID_REQUEST("COMMON_001", "Invalid request", HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER("COMMON_002", "Invalid parameter", HttpStatus.BAD_REQUEST),

    // AI Core (Python, port 8082)
    AI_CORE_UNAVAILABLE("AI_001", "AI service is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    AI_CORE_ERROR("AI_002", "AI service request failed", HttpStatus.BAD_GATEWAY),

    LEARNING_PATH_NOT_FOUND("LEARNING_PATH_001", "Learning path not found", HttpStatus.NOT_FOUND),
    LEARNING_PATH_EMPTY("LEARNING_PATH_002", "No concepts remain to create a learning path", HttpStatus.BAD_REQUEST),
    LEARNING_PATH_GRAPH_CHANGED("LEARNING_PATH_003", "Knowledge graph changed; preview the learning path again", HttpStatus.CONFLICT),
    LEARNING_PATH_STEP_NOT_FOUND("LEARNING_PATH_004", "Step not found in this learning path", HttpStatus.NOT_FOUND),
    LEARNING_PATH_STEP_LOCKED("LEARNING_PATH_005", "Complete all steps in earlier stages first", HttpStatus.CONFLICT),
    LEARNING_PATH_NOT_ACTIVE("LEARNING_PATH_006", "This learning path is not active", HttpStatus.CONFLICT),
    LEARNING_PATH_STEP_COMPLETED("LEARNING_PATH_007", "A completed step cannot be reopened", HttpStatus.CONFLICT),
    LEARNING_PATH_STAGES_UNAVAILABLE("LEARNING_PATH_008", "Stage information is missing or invalid; create a new learning path", HttpStatus.CONFLICT),

    // Chat: do not disclose whether another user's session exists.
    CHAT_SESSION_NOT_FOUND("CHAT_001", "Chat session not found", HttpStatus.NOT_FOUND),
    CHAT_REPLY_NOT_FOUND("CHAT_002", "Reply message not found in this session", HttpStatus.NOT_FOUND),
    CHAT_REPLY_NOT_READY("CHAT_003", "Only completed non-empty messages can be replied to", HttpStatus.BAD_REQUEST),
    CHAT_MESSAGE_NOT_FOUND("CHAT_004", "Chat message not found in this session", HttpStatus.NOT_FOUND),
    CHAT_FEEDBACK_NOT_ALLOWED("CHAT_005", "Only completed BOT messages can receive feedback", HttpStatus.BAD_REQUEST),


    // System
    INTERNAL_SERVER_ERROR("SYSTEM_001", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR),
    ;

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(String code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

}
