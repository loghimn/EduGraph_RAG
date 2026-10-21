package fu.coreservice.service;

import fu.coreservice.dto.chat.MessageFeedbackRequest;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import fu.coreservice.service.impl.MessageFeedbackServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class MessageFeedbackServiceTest {
    @Mock UserRepository userRepository;
    @Mock CourseRepository courseRepository;
    @Mock CourseEnrollmentRepository courseEnrollmentRepository;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock MessageFeedbackRepository messageFeedbackRepository;
    @InjectMocks MessageFeedbackServiceImpl service;

    private final User user = User.builder().userId(2L).email("learner@example.com").build();
    private final Course course = Course.builder().courseId(7L).user(User.builder().userId(1L).build()).build();
    private final ChatSession session = ChatSession.builder().sessionId(10L).learner(user).course(course).build();
    private final ChatMessage message = ChatMessage.builder().messageId(21L).session(session)
            .senderType(SenderType.BOT).content("Answer").build();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user.getEmail(), null, List.of()));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void allowSession() {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        when(courseEnrollmentRepository.existsByCourse_CourseIdAndUser(7L, user)).thenReturn(true);
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, user))
                .thenReturn(Optional.of(session));
    }

    private MessageFeedbackRequest request(short rating, String comment) {
        MessageFeedbackRequest request = new MessageFeedbackRequest();
        request.setRating(rating);
        request.setComment(comment);
        return request;
    }

    @Test
    void createsFeedbackWithAuthenticatedUserAndNormalizedComment() {
        allowSession();
        when(chatMessageRepository.findForFeedbackUpdate(21L, session)).thenReturn(Optional.of(message));
        when(messageFeedbackRepository.findByMessage(message)).thenReturn(Optional.empty());
        when(messageFeedbackRepository.saveAndFlush(any())).thenAnswer(call -> {
            MessageFeedback feedback = call.getArgument(0);
            assertSame(user, feedback.getUser());
            assertSame(message, feedback.getMessage());
            feedback.setFeedbackId(30L);
            return feedback;
        });
        var response = service.saveFeedback(7L, 10L, 21L, request((short) -1, "  Need an example  "));
        assertEquals(30L, response.getFeedbackId());
        assertEquals((short) -1, response.getRating());
        assertEquals("Need an example", response.getComment());
    }

    @Test
    void updatesSameFeedbackRatherThanCreatingAnotherRow() {
        allowSession();
        when(chatMessageRepository.findForFeedbackUpdate(21L, session)).thenReturn(Optional.of(message));
        MessageFeedback existing = MessageFeedback.builder().feedbackId(30L).message(message).user(user)
                .rating((short) -1).comment("Old comment").build();
        when(messageFeedbackRepository.findByMessage(message)).thenReturn(Optional.of(existing));
        when(messageFeedbackRepository.saveAndFlush(existing)).thenReturn(existing);
        var response = service.saveFeedback(7L, 10L, 21L, request((short) 1, " "));
        assertEquals(30L, response.getFeedbackId());
        assertEquals((short) 1, response.getRating());
        assertNull(response.getComment());
    }

    @Test
    void getWithoutFeedbackReturnsNull() {
        allowSession();
        when(chatMessageRepository.findByMessageIdAndSession(21L, session)).thenReturn(Optional.of(message));
        when(messageFeedbackRepository.findByMessage(message)).thenReturn(Optional.empty());
        assertNull(service.getFeedback(7L, 10L, 21L));
        verify(chatMessageRepository, never()).findForFeedbackUpdate(any(), any());
    }

    @Test
    void deletionRemovesOnlyFeedbackAndIsIdempotent() {
        allowSession();
        when(chatMessageRepository.findForFeedbackUpdate(21L, session)).thenReturn(Optional.of(message));
        MessageFeedback feedback = MessageFeedback.builder().message(message).user(user).build();
        when(messageFeedbackRepository.findByMessage(message)).thenReturn(Optional.of(feedback), Optional.empty());
        service.deleteFeedback(7L, 10L, 21L);
        service.deleteFeedback(7L, 10L, 21L);
        verify(messageFeedbackRepository).delete(feedback);
        verify(chatMessageRepository, never()).delete(any());
    }

    @Test
    void foreignSessionCannotBeUsedForFeedback() {
        allowSession();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, user)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.CHAT_SESSION_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getFeedback(7L, 10L, 21L)).getErrorCode());
        verifyNoInteractions(chatMessageRepository, messageFeedbackRepository);
    }

    @Test
    void foreignOrMissingMessageIsRejected() {
        allowSession();
        when(chatMessageRepository.findForFeedbackUpdate(21L, session)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.CHAT_MESSAGE_NOT_FOUND, assertThrows(AppException.class,
                () -> service.saveFeedback(7L, 10L, 21L, request((short) 1, null))).getErrorCode());
        verifyNoInteractions(messageFeedbackRepository);
    }

    @Test
    void userMessagesAndUnfinishedBotsCannotReceiveFeedback() {
        allowSession();
        when(chatMessageRepository.findForFeedbackUpdate(21L, session)).thenReturn(Optional.of(message));
        message.setSenderType(SenderType.USER);
        assertEquals(ErrorCode.CHAT_FEEDBACK_NOT_ALLOWED, assertThrows(AppException.class,
                () -> service.saveFeedback(7L, 10L, 21L, request((short) 1, null))).getErrorCode());
        message.setSenderType(SenderType.BOT);
        for (ChatMessageStatus status : List.of(ChatMessageStatus.STREAMING, ChatMessageStatus.FAILED,
                ChatMessageStatus.INTERRUPTED)) {
            message.setStatus(status);
            assertEquals(ErrorCode.CHAT_FEEDBACK_NOT_ALLOWED, assertThrows(AppException.class,
                    () -> service.saveFeedback(7L, 10L, 21L, request((short) 1, null))).getErrorCode());
        }
        verifyNoInteractions(messageFeedbackRepository);
    }

    @Test
    void neverModifiesFeedbackWithAnotherUserId() {
        allowSession();
        when(chatMessageRepository.findForFeedbackUpdate(21L, session)).thenReturn(Optional.of(message));
        when(messageFeedbackRepository.findByMessage(message)).thenReturn(Optional.of(MessageFeedback.builder()
                .message(message).user(User.builder().userId(99L).build()).build()));
        assertEquals(ErrorCode.ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.deleteFeedback(7L, 10L, 21L)).getErrorCode());
        verify(messageFeedbackRepository, never()).delete(any());
    }

    @Test
    void invalidRatingOrOversizedCommentIsRejectedBeforeDatabaseAccess() {
        assertThrows(AppException.class, () -> service.saveFeedback(7L, 10L, 21L, request((short) 0, null)));
        assertThrows(AppException.class, () -> service.saveFeedback(7L, 10L, 21L, request((short) 1, "x".repeat(2001))));
        verifyNoInteractions(userRepository, chatSessionRepository, messageFeedbackRepository);
    }
}
