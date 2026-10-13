package fu.coreservice.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request lập lộ trình học từ frontend; Java map sang AiLearningPathRequest (snake_case)
 * gọi POST /learning-paths/plan của Python. Boolean null -> default true (giống Python).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearningPathPlanRequest {

    private Long documentId;

    private List<String> goalConceptIds;

    private List<String> knownConceptIds;

    private Boolean includeExternalPrereqs;

    private Boolean assumeKnownPrerequisites;
}
