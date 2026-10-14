package fu.coreservice.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Response của GET /documents/{id}/status (Python) — trạng thái trong Neo4j.
 * status là tiến trình xử lý (PROCESSING/DONE/FAILED), KHÔNG phải UploadStatus của Postgres.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiDocumentStatusResponse {

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("course_id")
    private String courseId;

    private String status;

    private String progress;

    @JsonProperty("current_step")
    private String currentStep;

    private String error;
}
