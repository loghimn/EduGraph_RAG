package fu.coreservice.dto.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Body gửi tới POST /learning-paths/plan của Python (snake_case theo LearningPathPlanRequest).
 * include_external_prerequisites / assume_known_prerequisites có default true phía Python
 * nên luôn được serialize rõ ràng (không gửi null).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiLearningPathRequest {

    @JsonProperty("course_id")
    private String courseId;

    @JsonProperty("user_id")
    private String userId;

    @JsonProperty("document_id")
    private String documentId;

    @JsonProperty("goal_concept_ids")
    private List<String> goalConceptIds;

    @JsonProperty("known_concept_ids")
    private List<String> knownConceptIds;

    @JsonProperty("include_external_prereqs")
    private Boolean includeExternalPrereqs;

    @JsonProperty("assume_known_prerequisites")
    private Boolean assumeKnownPrerequisites;
}
