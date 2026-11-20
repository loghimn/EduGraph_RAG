package fu.coreservice.dto.ai;

import jakarta.validation.constraints.*;

/** Per-course teaching preferences; these never override source/citation rules. */
public record CourseAiSettings(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double similarityThreshold,
        @NotNull @Min(0) @Max(6) Integer traversalDepth,
        @NotNull @Size(max = 4000) String promptTemplate) {
    public static CourseAiSettings defaults() { return new CourseAiSettings(0.35, 3, ""); }
}
