package fu.coreservice.service;

import fu.coreservice.dto.chat.ChatSessionCreateRequest;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import fu.coreservice.service.impl.ChatSessionServiceImpl;
import fu.coreservice.dto.chat.ChatRequest;
import fu.coreservice.dto.chat.ChatTurn;
import fu.coreservice.dto.chat.ChatSource;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatSessionServiceTest {
    @Mock UserRepository userRepository;
    @Mock CourseRepository courseRepository;
    @Mock CourseEnrollmentRepository courseEnrollmentRepository;
    @Mock ChatSessionRepository chatSessionRepository;
    @Mock ChatMessageRepository chatMessageRepository;
    @Mock MessageCitationRepository messageCitationRepository;
    @InjectMocks ChatSessionServiceImpl service;

    private final User learner = User.builder().userId(2L).email("learner@example.com").build();
    private final Course course = Course.builder().courseId(7L)
            .user(User.builder().userId(1L).build()).isPublic(true).build();

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(learner.getEmail(), null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void allowLearner() {
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        when(courseEnrollmentRepository.existsByCourse_CourseIdAndUser(7L, learner)).thenReturn(true);
    }

    @Test
    void replyToOldBotPersistsReferenceAndCapturesItsOriginalQuestion() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        ChatMessage originalQuestion = ChatMessage.builder().messageId(5L).session(session)
                .senderType(SenderType.USER).content("What is Java Servlet?").build();
        ChatMessage target = ChatMessage.builder().messageId(6L).session(session).senderType(SenderType.BOT)
                .content("Servlet handles HTTP requests").replyToMessage(originalQuestion).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        when(chatMessageRepository.findByMessageIdAndSession(6L, session)).thenReturn(Optional.of(target));
        when(chatMessageRepository.saveAndFlush(any())).thenAnswer(call -> {
            ChatMessage message = call.getArgument(0);
            message.setMessageId(message.getSenderType() == SenderType.USER ? 20L : 21L);
            return message;
        });
        ChatTurn turn = service.beginTurn(7L, 10L, ChatRequest.builder()
                .message("Give me an example").replyToMessageId(6L).build());
        assertTrue(turn.history().isEmpty()); // selected message is outside the recent window
        assertEquals(2, turn.replyContext().size());
        assertEquals("What is Java Servlet?", turn.replyContext().getFirst().content());
        assertEquals(target.getContent(), turn.replyContext().getLast().content());
        ArgumentCaptor<ChatMessage> saved = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).saveAndFlush(saved.capture());
        assertSame(target, saved.getAllValues().getFirst().getReplyToMessage());
        assertSame(saved.getAllValues().getFirst(), saved.getAllValues().getLast().getReplyToMessage());
    }

    @Test
    void replyCannotReferenceMessageOutsideOwnedSession() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        when(chatMessageRepository.findByMessageIdAndSession(99L, session)).thenReturn(Optional.empty());
        assertEquals(ErrorCode.CHAT_REPLY_NOT_FOUND, assertThrows(AppException.class,
                () -> service.beginTurn(7L, 10L, ChatRequest.builder()
                        .message("Explain this").replyToMessageId(99L).build())).getErrorCode());
        verify(chatMessageRepository, never()).saveAndFlush(any());
    }

    @Test
    void replyCannotTargetStreamingOrFailedAnswer() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        for (ChatMessageStatus status : List.of(ChatMessageStatus.STREAMING, ChatMessageStatus.FAILED,
                ChatMessageStatus.INTERRUPTED)) {
            when(chatMessageRepository.findByMessageIdAndSession(6L, session)).thenReturn(Optional.of(
                    ChatMessage.builder().messageId(6L).session(session).content("Partial").status(status).build()));
            assertEquals(ErrorCode.CHAT_REPLY_NOT_READY, assertThrows(AppException.class,
                    () -> service.beginTurn(7L, 10L, ChatRequest.builder()
                            .message("Explain this").replyToMessageId(6L).build())).getErrorCode());
        }
        verify(chatMessageRepository, never()).saveAndFlush(any());
    }

    @Test
    void contextLoadsOnlyCompletedMessagesFromOwnedSessionBeforeCurrentTurn() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        when(chatMessageRepository.findBySessionAndStatusOrderByCreatedAtDescMessageIdDesc(
                session, ChatMessageStatus.COMPLETED, PageRequest.of(0, 12)))
                .thenReturn(List.of(
                        ChatMessage.builder().senderType(SenderType.BOT).content("Learn Java and HTTP").build(),
                        ChatMessage.builder().senderType(SenderType.USER).content("Java Servlet prerequisites?").build()));
        when(chatMessageRepository.saveAndFlush(any())).thenAnswer(call -> {
            ChatMessage message = call.getArgument(0);
            message.setMessageId(message.getSenderType() == SenderType.USER ? 20L : 21L);
            return message;
        });
        ChatTurn turn = service.beginTurn(7L, 10L, ChatRequest.builder().message("Which first?").build());
        assertEquals(2, turn.history().size());
        assertEquals("user", turn.history().getFirst().role());
        assertEquals("assistant", turn.history().getLast().role());
        assertEquals("Learn Java and HTTP", turn.history().getLast().content());
        var ordered = inOrder(chatMessageRepository);
        ordered.verify(chatMessageRepository).findBySessionAndStatusOrderByCreatedAtDescMessageIdDesc(
                session, ChatMessageStatus.COMPLETED, PageRequest.of(0, 12));
        ordered.verify(chatMessageRepository, times(2)).saveAndFlush(any());
    }

    @Test
    void contextHasCharacterBudgetAndTruncatesLongMessages() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        List<ChatMessage> messages = java.util.stream.IntStream.range(0, 12)
                .mapToObj(i -> ChatMessage.builder().senderType(SenderType.BOT).content("x".repeat(5000)).build())
                .toList();
        when(chatMessageRepository.findBySessionAndStatusOrderByCreatedAtDescMessageIdDesc(
                session, ChatMessageStatus.COMPLETED, PageRequest.of(0, 12))).thenReturn(messages);
        when(chatMessageRepository.saveAndFlush(any())).thenAnswer(call -> {
            ChatMessage message = call.getArgument(0);
            message.setMessageId(message.getSenderType() == SenderType.USER ? 20L : 21L);
            return message;
        });
        ChatTurn turn = service.beginTurn(7L, 10L, ChatRequest.builder().message("Which first?").build());
        assertEquals(12000, turn.history().stream().mapToInt(m -> m.content().length()).sum());
        assertTrue(turn.history().stream().allMatch(m -> m.content().length() <= 4000));
    }

    @Test
    void beginTurnSavesQuestionAndStreamingBotInOwnedSession() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        when(chatMessageRepository.saveAndFlush(any())).thenAnswer(call -> {
            ChatMessage message = call.getArgument(0);
            message.setMessageId(message.getSenderType() == SenderType.USER ? 20L : 21L);
            return message;
        });
        ChatTurn turn = service.beginTurn(7L, 10L, ChatRequest.builder().message("OOP?").build());
        assertEquals(new ChatTurn(10L, 2L, 20L, 21L), turn);
        ArgumentCaptor<ChatMessage> messages = ArgumentCaptor.forClass(ChatMessage.class);
        verify(chatMessageRepository, times(2)).saveAndFlush(messages.capture());
        assertEquals("OOP?", messages.getAllValues().getFirst().getContent());
        assertEquals(ChatMessageStatus.COMPLETED, messages.getAllValues().getFirst().getStatus());
        assertEquals(ChatMessageStatus.STREAMING, messages.getAllValues().get(1).getStatus());
        assertNotNull(session.getLastActive());
    }

    @Test
    void beginTurnCannotWriteToAnotherUsersSession() {
        allowLearner();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(99L, course, learner))
                .thenReturn(Optional.empty());
        assertThrows(AppException.class,
                () -> service.beginTurn(7L, 99L, ChatRequest.builder().message("OOP?").build()));
        verifyNoInteractions(chatMessageRepository);
    }

    @Test
    void finishTurnStoresAnswerCitationsAndTouchesSession() {
        ChatSession session = ChatSession.builder().sessionId(10L).build();
        ChatMessage answer = ChatMessage.builder().messageId(21L).session(session)
                .status(ChatMessageStatus.STREAMING).build();
        when(chatMessageRepository.findById(21L)).thenReturn(Optional.of(answer));
        service.finishTurn(new ChatTurn(10L, 2L, 20L, 21L), "Answer [1]", ChatMessageStatus.COMPLETED,
                null, 500, List.of(new ChatSource(1, 40L, "chunk1", 3, 0, "Source")), false);
        assertEquals("Answer [1]", answer.getContent());
        assertEquals(ChatMessageStatus.COMPLETED, answer.getStatus());
        assertEquals(500, answer.getLatencyMs());
        verify(messageCitationRepository).saveAll(argThat(items -> {
            MessageCitation citation = items.iterator().next();
            return citation.getMessage() == answer && citation.getCitationNumber() == 1
                    && citation.getDocumentId().equals(40L) && citation.getVectorChunkId().equals("chunk1");
        }));
        assertNotNull(session.getLastActive());
        verify(chatSessionRepository).save(session);
    }

    @Test
    void clarificationRemovesBotPlaceholderWithoutSavingCitations() {
        ChatSession session = ChatSession.builder().sessionId(10L).build();
        ChatMessage answer = ChatMessage.builder().messageId(21L).session(session)
                .status(ChatMessageStatus.STREAMING).build();
        when(chatMessageRepository.findById(21L)).thenReturn(Optional.of(answer));
        service.finishTurn(new ChatTurn(10L, 2L, 20L, 21L), "", ChatMessageStatus.COMPLETED,
                null, 500, List.of(), true);
        verify(chatMessageRepository).delete(answer);
        verify(chatMessageRepository, never()).save(any());
        verifyNoInteractions(messageCitationRepository);
    }

    @Test
    void createsSessionForAuthenticatedLearnerWithDefaultTitle() {
        allowLearner();
        when(chatSessionRepository.saveAndFlush(any())).thenAnswer(call -> {
            ChatSession session = call.getArgument(0);
            assertSame(learner, session.getLearner());
            assertSame(course, session.getCourse());
            assertNull(session.getPlan());
            session.setSessionId(10L);
            return session;
        });
        var response = service.createSession(7L, new ChatSessionCreateRequest());
        assertEquals(10L, response.getSessionId());
        assertEquals("New conversation", response.getTitle());
    }

    @Test
    void ownerCanCreateSessionWithoutEnrollment() {
        course.setUser(learner);
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        when(chatSessionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        ChatSessionCreateRequest request = new ChatSessionCreateRequest();
        request.setTitle("  OOP questions  ");
        assertEquals("OOP questions", service.createSession(7L, request).getTitle());
        verifyNoInteractions(courseEnrollmentRepository);
    }

    @Test
    void deniesNonEnrolledUserEvenForPublicCourse() {
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.createSession(7L, new ChatSessionCreateRequest())).getErrorCode());
        verifyNoInteractions(chatSessionRepository);
    }

    @Test
    void listsOnlyCurrentUsersSessionsInCourseWithStableSort() {
        allowLearner();
        Pageable pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "lastActive", "sessionId"));
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findByLearnerAndCourse(learner, course, pageable))
                .thenReturn(new PageImpl<>(List.of(session), pageable, 1));
        var response = service.getMySessions(7L, 0, 10);
        assertEquals(1, response.getTotalElements());
        assertEquals(10L, response.getContent().getFirst().getSessionId());
    }

    @Test
    void rejectsMissingForeignOrWrongCourseSessionBeforeReadingMessages() {
        allowLearner();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(99L, course, learner))
                .thenReturn(Optional.empty());
        assertEquals(ErrorCode.CHAT_SESSION_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getMessages(7L, 99L, 0, 20)).getErrorCode());
        verifyNoInteractions(chatMessageRepository, messageCitationRepository);
    }

    @Test
    void courseOwnerCannotReadAnotherUsersSession() {
        course.setUser(learner);
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(99L, course, learner))
                .thenReturn(Optional.empty());
        assertEquals(ErrorCode.CHAT_SESSION_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getMessages(7L, 99L, 0, 20)).getErrorCode());
        verifyNoInteractions(chatMessageRepository, messageCitationRepository);
    }

    @Test
    void returnsMessageHistoryWithCitationsAttachedToCorrectMessage() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        ChatMessage question = ChatMessage.builder().messageId(20L).session(session)
                .senderType(SenderType.USER).content("OOP?").build();
        ChatMessage answer = ChatMessage.builder().messageId(21L).session(session)
                .senderType(SenderType.BOT).content("Explanation [1]").replyToMessage(question).build();
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "createdAt", "messageId"));
        when(chatMessageRepository.findBySession(session, pageable))
                .thenReturn(new PageImpl<>(List.of(question, answer), pageable, 2));
        when(messageCitationRepository.findByMessage_MessageIdInOrderByCitationNumberAscCitationIdAsc(List.of(20L, 21L)))
                .thenReturn(List.of(MessageCitation.builder().citationId(30L).message(answer)
                        .citationNumber(1).documentId(40L).vectorChunkId("chunk1").pageNumber(3).build()));
        var response = service.getMessages(7L, 10L, 0, 20);
        assertTrue(response.getContent().getFirst().getCitations().isEmpty());
        var bot = response.getContent().get(1);
        assertEquals(ChatMessageStatus.COMPLETED, bot.getStatus());
        assertEquals(20L, bot.getReplyToMessageId());
        assertEquals(1, bot.getCitations().getFirst().getCitationNumber());
        assertEquals(40L, bot.getCitations().getFirst().getDocumentId());
    }

    @Test
    void emptyHistoryDoesNotQueryCitations() {
        allowLearner();
        ChatSession session = ChatSession.builder().sessionId(10L).course(course).learner(learner).build();
        when(chatSessionRepository.findBySessionIdAndCourseAndLearner(10L, course, learner))
                .thenReturn(Optional.of(session));
        when(chatMessageRepository.findBySession(eq(session), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));
        assertTrue(service.getMessages(7L, 10L, 0, 20).getContent().isEmpty());
        verifyNoInteractions(messageCitationRepository);
    }

    @Test
    void invalidPaginationIsRejectedBeforeDatabaseAccess() {
        for (int[] values : List.of(new int[]{-1, 10}, new int[]{0, 0}, new int[]{0, 101})) {
            assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(AppException.class,
                    () -> service.getMySessions(7L, values[0], values[1])).getErrorCode());
        }
        verifyNoInteractions(userRepository, courseRepository, chatSessionRepository);
    }
}
