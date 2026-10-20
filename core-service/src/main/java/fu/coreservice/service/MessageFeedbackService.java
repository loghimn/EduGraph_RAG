package fu.coreservice.service;

import fu.coreservice.dto.chat.MessageFeedbackRequest;
import fu.coreservice.dto.chat.MessageFeedbackResponse;

public interface MessageFeedbackService {
    MessageFeedbackResponse saveFeedback(Long courseId, Long sessionId, Long messageId, MessageFeedbackRequest request);
    MessageFeedbackResponse getFeedback(Long courseId, Long sessionId, Long messageId);
    void deleteFeedback(Long courseId, Long sessionId, Long messageId);
}
