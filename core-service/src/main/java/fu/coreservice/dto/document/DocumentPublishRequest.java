package fu.coreservice.dto.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record DocumentPublishRequest(
        @JsonProperty("review_id") @NotBlank String reviewId,
        @JsonProperty("excluded_concepts") @NotNull @Size(max = 10000) List<String> excludedConcepts,
        @JsonProperty("excluded_edges") @NotNull @Size(max = 100000) List<String> excludedEdges) {}
