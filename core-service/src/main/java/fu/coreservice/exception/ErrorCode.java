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

    // Common
    INVALID_REQUEST("COMMON_001", "Invalid request", HttpStatus.BAD_REQUEST),
    INVALID_PARAMETER("COMMON_002", "Invalid parameter", HttpStatus.BAD_REQUEST),


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
