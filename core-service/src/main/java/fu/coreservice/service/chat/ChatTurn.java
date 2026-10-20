package fu.coreservice.service.chat;

import fu.coreservice.dto.ai.AiChatHistoryMessage;
import java.util.List;

/** IDs captured on the request thread; async streaming must not read SecurityContext. */
public record ChatTurn(Long sessionId, Long userId, Long userMessageId, Long assistantMessageId,
                       List<AiChatHistoryMessage> history) {
    public ChatTurn {
        history = List.copyOf(history);
    }

    public ChatTurn(Long sessionId, Long userId, Long userMessageId, Long assistantMessageId) {
        this(sessionId, userId, userMessageId, assistantMessageId, List.of());
    }
}
