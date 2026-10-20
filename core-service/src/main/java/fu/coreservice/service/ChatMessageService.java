package fu.coreservice.service;

import fu.coreservice.dto.request.ChatRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

public interface ChatMessageService {
    StreamingResponseBody sendMessage(Long courseId, Long sessionId, ChatRequest request);
}
