package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.ChatMessageResponse;
import fu.coreservice.dto.chat.ChatSessionCreateRequest;
import fu.coreservice.dto.chat.ChatSessionResponse;
import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.entity.ChatMessageStatus;
import fu.coreservice.service.chat.ChatSource;
import fu.coreservice.service.chat.ChatTurn;
import java.util.List;

public interface ChatSessionService {
    ChatSessionResponse createSession(Long courseId, ChatSessionCreateRequest request);
    PageResponse<ChatSessionResponse> getMySessions(Long courseId, int page, int size);
    PageResponse<ChatMessageResponse> getMessages(Long courseId, Long sessionId, int page, int size);
    ChatTurn beginTurn(Long courseId, Long sessionId, ChatRequest request);
    void finishTurn(ChatTurn turn, String content, ChatMessageStatus status, String error,
                    int latencyMs, List<ChatSource> sources, boolean clarification);
}
