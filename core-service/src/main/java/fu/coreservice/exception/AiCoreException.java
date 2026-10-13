package fu.coreservice.exception;

import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * Lỗi khi gọi AI core-service (Python FastAPI, port 8082).
 * Mang theo HTTP status gốc từ Python để relay về client đúng contract
 * (ví dụ 409 của /learning-paths/plan).
 */
@Getter
public class AiCoreException extends RuntimeException {

    private final int httpStatus;
    private final String detail;
    private final Map<String, Object> body;

    public AiCoreException(int httpStatus, String detail, Map<String, Object> body) {
        super(detail);
        this.httpStatus = httpStatus;
        this.detail = detail;
        this.body = body == null ? Collections.emptyMap() : body;
    }
}
