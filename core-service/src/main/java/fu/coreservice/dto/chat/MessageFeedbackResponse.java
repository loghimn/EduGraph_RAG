package fu.coreservice.dto.chat;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@Builder
public class MessageFeedbackResponse {
    private Long feedbackId;
    private Long messageId;
    private Short rating;
    private String comment;
    private LocalDateTime createdAt;
}
