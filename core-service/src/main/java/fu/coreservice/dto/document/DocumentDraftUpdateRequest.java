package fu.coreservice.dto.document;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentDraftUpdateRequest(
        @JsonProperty("review_id") @NotBlank @Size(max = 100) String reviewId) {}
