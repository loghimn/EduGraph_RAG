package fu.coreservice.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body gửi tới POST /ingest của Python (snake_case theo IngestRequest của FastAPI).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiIngestRequest {

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("course_id")
    private String courseId;

    @JsonProperty("user_id")
    private String userId;

    private String filename;

    private boolean restart;

    @JsonProperty("run_id")
    private String runId;
}
