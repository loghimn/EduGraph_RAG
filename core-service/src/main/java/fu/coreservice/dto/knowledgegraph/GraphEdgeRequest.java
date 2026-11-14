package fu.coreservice.dto.knowledgegraph;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GraphEdgeRequest(
        @JsonProperty("source_id") @NotBlank @Size(max = 200) String sourceId,
        @JsonProperty("target_id") @NotBlank @Size(max = 200) String targetId,
        @Size(max = 2000) String note) {
}
