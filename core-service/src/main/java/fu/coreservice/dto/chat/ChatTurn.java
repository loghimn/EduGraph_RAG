package fu.coreservice.dto.chat;

import fu.coreservice.dto.ai.AiChatHistoryMessage;
import java.util.List;

/** IDs captured on the request thread; async streaming must not read SecurityContext. */
public record ChatTurn(Long sessionId, Long userId, Long userMessageId, Long assistantMessageId,
                       List<AiChatHistoryMessage> history, List<AiChatHistoryMessage> replyContext) {
    public ChatTurn {
        history = List.copyOf(history);
        replyContext = List.copyOf(replyContext);
    }

    public ChatTurn(Long sessionId, Long userId, Long userMessageId, Long assistantMessageId,
                    List<AiChatHistoryMessage> history) {
        this(sessionId, userId, userMessageId, assistantMessageId, history, List.of());
    }

    public ChatTurn(Long sessionId, Long userId, Long userMessageId, Long assistantMessageId) {
        this(sessionId, userId, userMessageId, assistantMessageId, List.of());
    }
}
