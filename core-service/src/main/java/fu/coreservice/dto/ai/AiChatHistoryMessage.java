package fu.coreservice.dto.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record AiChatHistoryMessage(String role, String content,
                                   @JsonProperty("source_course_ids") List<String> sourceCourseIds) {
    public AiChatHistoryMessage {
        sourceCourseIds = sourceCourseIds == null ? List.of() : List.copyOf(sourceCourseIds);
    }

    public AiChatHistoryMessage(String role, String content) {
        this(role, content, List.of());
    }
}
