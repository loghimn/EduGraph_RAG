package fu.coreservice.dto.chat;

import fu.coreservice.entity.ChatMessageStatus;
import fu.coreservice.entity.SenderType;
import lombok.Builder;
import lombok.Getter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ChatMessageResponse {
    private Long messageId;
    private Long replyToMessageId;
    private SenderType senderType;
    private String content;
    private ChatMessageStatus status;
    private Integer tokensUsed;
    private Integer latencyMs;
    private LocalDateTime createdAt;
    private List<ChatCitationResponse> citations;
}
