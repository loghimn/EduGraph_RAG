package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.ChatMessageResponse;
import fu.coreservice.dto.chat.ChatSessionCreateRequest;
import fu.coreservice.dto.chat.ChatSessionResponse;
import fu.coreservice.dto.chat.ChatRequest;
import fu.coreservice.entity.ChatMessageStatus;
import fu.coreservice.dto.chat.ChatSource;
import fu.coreservice.dto.chat.ChatTurn;
import java.util.List;
import java.util.Map;

public interface ChatSessionService {
    ChatSessionResponse createSession(Long courseId, ChatSessionCreateRequest request);
    ChatSessionResponse createGlobalSession(ChatSessionCreateRequest request);
    PageResponse<ChatSessionResponse> getMySessions(Long courseId, int page, int size);
    PageResponse<ChatSessionResponse> getGlobalSessions(int page, int size);
    PageResponse<ChatMessageResponse> getMessages(Long courseId, Long sessionId, int page, int size);
    PageResponse<ChatMessageResponse> getGlobalMessages(Long sessionId, int page, int size);
    ChatTurn beginTurn(Long courseId, Long sessionId, ChatRequest request);
    ChatTurn beginGlobalTurn(Long sessionId, ChatRequest request);
    List<String> getGlobalChatCourseIds();
    Map<String, String> getChatCourseNames(List<String> courseIds);
    void finishTurn(ChatTurn turn, String content, ChatMessageStatus status, String error,
                    int latencyMs, List<ChatSource> sources, boolean clarification);
    void finishClarification(ChatTurn turn, List<fu.coreservice.dto.chat.ClarificationCandidate> candidates, int latencyMs);
}
