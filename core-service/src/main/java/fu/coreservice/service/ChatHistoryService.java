package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.*;

public interface ChatHistoryService {
    PageResponse<ChatSessionResponse> search(Long courseId, String query, int page, int size);
    ChatSessionResponse get(Long courseId, Long sessionId);
    ChatSessionResponse rename(Long courseId, Long sessionId, String title);
    void delete(Long courseId, Long sessionId);
    ChatMessageWindow recent(Long courseId, Long sessionId, Long beforeId, int size);
    CitationSourceResponse source(Long courseId, Long sessionId, Long citationId);
}
