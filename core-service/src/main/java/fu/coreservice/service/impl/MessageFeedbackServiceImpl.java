package fu.coreservice.service.impl;

import fu.coreservice.dto.chat.MessageFeedbackRequest;
import fu.coreservice.dto.chat.MessageFeedbackResponse;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import fu.coreservice.service.MessageFeedbackService;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MessageFeedbackServiceImpl implements MessageFeedbackService {
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageFeedbackRepository messageFeedbackRepository;

    @Override
    @Transactional
    public MessageFeedbackResponse saveFeedback(Long courseId, Long sessionId, Long messageId,
                                                MessageFeedbackRequest request) {
        if (request.getRating() == null || (request.getRating() != 1 && request.getRating() != -1)
                || (request.getComment() != null && request.getComment().length() > 2000)) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        OwnedSession owned = requireOwnedSession(courseId, sessionId);
        ChatMessage message = requireMessage(owned.session(), messageId, true);
        MessageFeedback feedback = findFeedback(message, owned.user());
        if (feedback == null) {
            feedback = MessageFeedback.builder().message(message).user(owned.user()).build();
        }
        feedback.setRating(request.getRating());
        String comment = request.getComment();
        feedback.setComment(comment == null || comment.isBlank() ? null : comment.trim());
        return toResponse(messageFeedbackRepository.saveAndFlush(feedback));
    }

    @Override
    @Transactional(readOnly = true)
    public MessageFeedbackResponse getFeedback(Long courseId, Long sessionId, Long messageId) {
        OwnedSession owned = requireOwnedSession(courseId, sessionId);
        ChatMessage message = requireMessage(owned.session(), messageId, false);
        MessageFeedback feedback = findFeedback(message, owned.user());
        return feedback == null ? null : toResponse(feedback);
    }

    @Override
    @Transactional
    public void deleteFeedback(Long courseId, Long sessionId, Long messageId) {
        OwnedSession owned = requireOwnedSession(courseId, sessionId);
        ChatMessage message = requireMessage(owned.session(), messageId, true);
        MessageFeedback feedback = findFeedback(message, owned.user());
        if (feedback != null) messageFeedbackRepository.delete(feedback);
    }

    private OwnedSession requireOwnedSession(Long courseId, Long sessionId) {
        User user = userRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        if (!course.getUser().getUserId().equals(user.getUserId())
                && !courseEnrollmentRepository.existsByCourse_CourseIdAndUser(courseId, user)) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }
        ChatSession session = chatSessionRepository.findBySessionIdAndCourseAndLearner(sessionId, course, user)
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        return new OwnedSession(user, session);
    }

    private ChatMessage requireMessage(ChatSession session, Long messageId, boolean lock) {
        ChatMessage message = (lock ? chatMessageRepository.findForFeedbackUpdate(messageId, session)
                : chatMessageRepository.findByMessageIdAndSession(messageId, session))
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_MESSAGE_NOT_FOUND));
        if (message.getSenderType() != SenderType.BOT || message.getStatus() != ChatMessageStatus.COMPLETED) {
            throw new AppException(ErrorCode.CHAT_FEEDBACK_NOT_ALLOWED);
        }
        return message;
    }

    private MessageFeedback findFeedback(ChatMessage message, User user) {
        MessageFeedback feedback = messageFeedbackRepository.findByMessage(message).orElse(null);
        if (feedback != null && !feedback.getUser().getUserId().equals(user.getUserId())) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        return feedback;
    }

    private MessageFeedbackResponse toResponse(MessageFeedback feedback) {
        return MessageFeedbackResponse.builder().feedbackId(feedback.getFeedbackId())
                .messageId(feedback.getMessage().getMessageId()).rating(feedback.getRating())
                .comment(feedback.getComment()).createdAt(feedback.getCreatedAt()).build();
    }

    private record OwnedSession(User user, ChatSession session) {}
}
