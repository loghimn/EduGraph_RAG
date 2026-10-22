package fu.coreservice.service;

import fu.coreservice.dto.chat.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public interface ChatMessageService {
    StreamingResponseBody sendMessage(Long courseId, Long sessionId, ChatRequest request);
}
