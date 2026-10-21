package fu.coreservice.dto.chat;

import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;

@Getter
@Builder
public class ChatSessionResponse {
    private Long sessionId;
    private Long courseId;
    private Long planId;
    private String title;
    private LocalDateTime startedAt;
    private LocalDateTime lastActive;
}
