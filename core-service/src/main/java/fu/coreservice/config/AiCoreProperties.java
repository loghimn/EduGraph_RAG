package fu.coreservice.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cấu hình kết nối tới AI core-service (Python FastAPI, mặc định http://localhost:8082).
 * internal-key PHẢI khớp INTERNAL_API_KEY trong .env của edugraph-ai-core.
 */
@Getter
@Setter
@Component
public class AiCoreProperties {

    @Value("${ai-core.base-url:http://localhost:8082}")
    private String baseUrl;

    @Value("${ai-core.internal-key:}")
    private String internalKey;

    @Value("${ai-core.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${ai-core.read-timeout-ms:30000}")
    private long readTimeoutMs;

    @Value("${ai-core.status-poll-interval-ms:15000}")
    private long statusPollIntervalMs;
}
