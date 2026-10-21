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
 * Body gửi tới POST /chat của Python (snake_case theo ChatRequest của FastAPI).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiChatRequest {

    @JsonProperty("course_id")
    private String courseId;

    @JsonProperty("user_id")
    private String userId;

    private String message;

    @JsonProperty("concept_ids")
    private List<String> conceptIds;

    private List<AiChatHistoryMessage> history;

    @JsonProperty("reply_context")
    private List<AiChatHistoryMessage> replyContext;
}
