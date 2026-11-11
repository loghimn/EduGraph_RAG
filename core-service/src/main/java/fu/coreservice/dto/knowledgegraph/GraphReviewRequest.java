package fu.coreservice.dto.knowledgegraph;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record GraphReviewRequest(
        @NotNull @Pattern(regexp = "CONFIRMED|REJECTED") String status,
        @Size(max = 2000) String note) {
}
