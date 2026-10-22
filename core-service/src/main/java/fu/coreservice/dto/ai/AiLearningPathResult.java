package fu.coreservice.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiLearningPathResult(
        @JsonProperty("course_id") String courseId,
        @JsonProperty("graph_version") Long graphVersion,
        String mode,
        List<Stage> stages,
        @JsonProperty("linear_order") List<String> linearOrder,
        List<String> warnings
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Stage(Integer level, List<Step> steps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(
            @JsonProperty("concept_id") String conceptId,
            String name,
            @JsonProperty("outside_document") Boolean outsideDocument
    ) {}
}
