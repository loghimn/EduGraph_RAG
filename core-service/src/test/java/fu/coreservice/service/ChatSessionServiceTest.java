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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
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
    @Mock ActivityAlertService alerts;
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
    void globalChatUsesPublicNonNotebookCoursesFromRepository() {
        when(courseRepository.findGlobalChatCourseIds()).thenReturn(List.of(26L, 28L));
        assertEquals(List.of("26", "28"), service.getGlobalChatCourseIds());
    }

    @Test
    void globalChatPreservesEmptyCourseScope() {
        when(courseRepository.findGlobalChatCourseIds()).thenReturn(List.of());
        assertTrue(service.getGlobalChatCourseIds().isEmpty());
    }

    @Test
    void chatCourseNamesComeFromCurrentRelationalMetadata() {
        course.setCourseName("Data structures");
        when(courseRepository.findAllById(List.of(7L))).thenReturn(List.of(course));
        assertEquals("Data structures", service.getChatCourseNames(List.of("7")).get("7"));
        course.setCourseName("Renamed course");
        assertEquals("Renamed course", service.getChatCourseNames(List.of("7")).get("7"));
    }

    @Test
    void emptyChatScopeDoesNotLoadCourseMetadata() {
        assertTrue(service.getChatCourseNames(List.of()).isEmpty());
        verifyNoInteractions(courseRepository);
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
        ChatMessage question = ChatMessage.builder().messageId(20L).session(session).senderType(SenderType.USER).build();
        ChatMessage answer = ChatMessage.builder().messageId(21L).session(session).replyToMessage(question)
                .status(ChatMessageStatus.STREAMING).build();
        when(chatMessageRepository.findById(21L)).thenReturn(Optional.of(answer));
        service.finishTurn(new ChatTurn(10L, 2L, 20L, 21L), "", ChatMessageStatus.COMPLETED,
                null, 500, List.of(), true);
        verify(chatMessageRepository).delete(answer);
        assertEquals(21L, question.getFinalizedClarificationAnswerId());
        verify(chatMessageRepository).saveAndFlush(question);
        verify(chatMessageRepository, never()).save(any());
        verifyNoInteractions(messageCitationRepository);
    }

    @Test
    void missingClarificationAnswerWithMatchingReceiptIsAlreadyFinalized() {
        ChatSession session = ChatSession.builder().sessionId(10L).build();
        ChatMessage question = ChatMessage.builder().messageId(20L).session(session).senderType(SenderType.USER)
                .finalizedClarificationAnswerId(21L).build();
        when(chatMessageRepository.findById(21L)).thenReturn(Optional.empty());
        when(chatMessageRepository.findById(20L)).thenReturn(Optional.of(question));
        service.finishTurn(new ChatTurn(10L, 2L, 20L, 21L), "", ChatMessageStatus.FAILED,
                "Lost acknowledgment", 999, List.of(), false);
        verify(chatMessageRepository, never()).saveAndFlush(any());
        verify(chatMessageRepository, never()).delete(any());
        verifyNoInteractions(chatSessionRepository, messageCitationRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"no_receipt", "wrong_answer", "wrong_session", "wrong_sender", "missing_question"})
    void missingAnswerWithoutMatchingClarificationReceiptRemainsNotFound(String scenario) {
        ChatSession session = ChatSession.builder().sessionId(scenario.equals("wrong_session") ? 99L : 10L).build();
        ChatMessage question = ChatMessage.builder().messageId(20L).session(session)
                .senderType(scenario.equals("wrong_sender") ? SenderType.BOT : SenderType.USER)
                .finalizedClarificationAnswerId(scenario.equals("no_receipt") ? null
                        : scenario.equals("wrong_answer") ? 99L : 21L).build();
        when(chatMessageRepository.findById(21L)).thenReturn(Optional.empty());
        when(chatMessageRepository.findById(20L)).thenReturn(scenario.equals("missing_question")
                ? Optional.empty() : Optional.of(question));
        assertEquals(ErrorCode.CHAT_MESSAGE_NOT_FOUND, assertThrows(AppException.class,
                () -> service.finishTurn(new ChatTurn(10L, 2L, 20L, 21L), "", ChatMessageStatus.FAILED,
                        "Retry", 999, List.of(), false)).getErrorCode());
        verify(chatMessageRepository, never()).saveAndFlush(any());
        verify(chatMessageRepository, never()).delete(any());
        verifyNoInteractions(chatSessionRepository, messageCitationRepository);
    }

    @ParameterizedTest
    @EnumSource(value = ChatMessageStatus.class, names = {"COMPLETED", "FAILED", "INTERRUPTED"})
    void retriesCannotModifyOrDeleteTerminalAnswers(ChatMessageStatus terminalStatus) {
        var lastActive = java.time.LocalDateTime.of(2026, 11, 9, 10, 0);
        ChatSession session = ChatSession.builder().sessionId(10L).lastActive(lastActive).build();
        ChatMessage answer = ChatMessage.builder().messageId(21L).session(session)
                .status(terminalStatus).content("Original [1]").errorMessage("Original error").latencyMs(500).build();
        when(chatMessageRepository.findById(21L)).thenReturn(Optional.of(answer));
        for (ChatMessageStatus retryStatus : List.of(ChatMessageStatus.FAILED, ChatMessageStatus.COMPLETED)) {
            for (boolean clarification : List.of(false, true)) {
                service.finishTurn(new ChatTurn(10L, 2L, 20L, 21L), "Retry", retryStatus,
                        "Retry error", 999, List.of(new ChatSource(1, 99L, "replacement", 1, 0, "Retry")),
                        clarification);
            }
        }
        assertEquals(terminalStatus, answer.getStatus());
        assertEquals("Original [1]", answer.getContent());
        assertEquals("Original error", answer.getErrorMessage());
        assertEquals(500, answer.getLatencyMs());
        assertEquals(lastActive, session.getLastActive());
        verify(chatMessageRepository, never()).saveAndFlush(any());
        verify(chatMessageRepository, never()).delete(any());
        verifyNoInteractions(chatSessionRepository, messageCitationRepository);
    }

    @ParameterizedTest
    @EnumSource(value = ChatMessageStatus.class, names = {"FAILED", "INTERRUPTED"})
    void streamingAnswersStillPersistPartialContentOnFailureOrInterruption(ChatMessageStatus status) {
        ChatSession session = ChatSession.builder().sessionId(10L).build();
        ChatMessage answer = ChatMessage.builder().messageId(21L).session(session)
                .status(ChatMessageStatus.STREAMING).build();
        when(chatMessageRepository.findById(21L)).thenReturn(Optional.of(answer));
        service.finishTurn(new ChatTurn(10L, 2L, 20L, 21L), "Partial", status,
                "Stream stopped", 500, List.of(), false);
        assertEquals(status, answer.getStatus());
        assertEquals("Partial", answer.getContent());
        assertEquals("Stream stopped", answer.getErrorMessage());
        verify(chatMessageRepository).saveAndFlush(answer);
        verify(chatSessionRepository).save(session);
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
    void listsCurrentUsersGlobalSessionsWithPaginationAndStableSort() {
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        Pageable pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "lastActive", "sessionId"));
        ChatSession session = ChatSession.builder().sessionId(10L).learner(learner).title("Global").build();
        when(chatSessionRepository.findByLearnerAndCourseIsNull(learner, pageable))
                .thenReturn(new PageImpl<>(List.of(session), pageable, 3));

        var response = service.getGlobalSessions(1, 2);

        assertEquals(3, response.getTotalElements());
        assertEquals(1, response.getPage());
        assertEquals(2, response.getSize());
        assertEquals(2, response.getTotalPages());
        assertEquals(10L, response.getContent().getFirst().getSessionId());
        assertNull(response.getContent().getFirst().getCourseId());
        verify(chatSessionRepository, never()).findByLearnerAndCourseIsPublic(any(), any(), any());
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

    @ParameterizedTest
    @ValueSource(strings = {"global", "enrolled", "owner"})
    void globalHistoryPreservesMessagesAndCitationsForAllowedSessions(String access) {
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        boolean global = access.equals("global");
        if (!global) {
            when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
            if (access.equals("owner")) {
                course.setUser(learner);
            } else {
                when(courseEnrollmentRepository.existsByCourse_CourseIdAndUser(7L, learner)).thenReturn(true);
            }
        }
        ChatSession session = ChatSession.builder().sessionId(10L).learner(learner)
                .course(global ? null : course).build();
        when(chatSessionRepository.findBySessionIdAndLearner(10L, learner)).thenReturn(Optional.of(session));
        ChatMessage answer = ChatMessage.builder().messageId(21L).session(session).senderType(SenderType.BOT)
                .content("Explanation [1]").build();
        Pageable pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.ASC, "createdAt", "messageId"));
        when(chatMessageRepository.findBySession(session, pageable))
                .thenReturn(new PageImpl<>(List.of(answer), pageable, 3));
        when(messageCitationRepository.findByMessage_MessageIdInOrderByCitationNumberAscCitationIdAsc(List.of(21L)))
                .thenReturn(List.of(MessageCitation.builder().citationId(30L).message(answer)
                        .citationNumber(1).documentId(40L).build()));

        var result = service.getGlobalMessages(10L, 1, 2);

        assertEquals(3, result.getTotalElements());
        assertEquals(1, result.getPage());
        assertEquals(2, result.getSize());
        var bot = result.getContent().getFirst();
        assertEquals("Explanation [1]", bot.getContent());
        assertEquals(40L, bot.getCitations().getFirst().getDocumentId());
        if (global) {
            verifyNoInteractions(courseRepository, courseEnrollmentRepository);
        }
    }

    @Test
    void globalRouteDeniesCourseHistoryAfterLearnerLeavesPublicCourse() {
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        ChatSession session = ChatSession.builder().sessionId(10L).learner(learner).course(course).build();
        when(chatSessionRepository.findBySessionIdAndLearner(10L, learner)).thenReturn(Optional.of(session));
        // No enrollment remains. Match the course history route's decision.
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.getGlobalMessages(10L, 0, 20)).getErrorCode());
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.getMessages(7L, 10L, 0, 20)).getErrorCode());
        verifyNoInteractions(chatMessageRepository, messageCitationRepository);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void globalHistoryKeepsMissingForeignAndPrivateSessionsNotFound(boolean privateSession) {
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        if (privateSession) {
            course.setPublic(false);
            ChatSession session = ChatSession.builder().sessionId(10L).learner(learner).course(course).build();
            when(chatSessionRepository.findBySessionIdAndLearner(10L, learner)).thenReturn(Optional.of(session));
        } else {
            when(chatSessionRepository.findBySessionIdAndLearner(10L, learner)).thenReturn(Optional.empty());
        }
        assertEquals(ErrorCode.CHAT_SESSION_NOT_FOUND, assertThrows(AppException.class,
                () -> service.getGlobalMessages(10L, 0, 20)).getErrorCode());
        verifyNoInteractions(courseRepository, courseEnrollmentRepository,
                chatMessageRepository, messageCitationRepository);
    }

    @Test
    void invalidPaginationIsRejectedBeforeDatabaseAccess() {
        for (int[] values : List.of(new int[]{-1, 10}, new int[]{0, 0}, new int[]{0, 101})) {
            assertEquals(ErrorCode.INVALID_PARAMETER, assertThrows(AppException.class,
                    () -> service.getMySessions(7L, values[0], values[1])).getErrorCode());
        }
        verifyNoInteractions(userRepository, courseRepository, chatSessionRepository);
    }
    @Test
    void notebookOwnerCanCreatePrivateChatWithoutEnrollment() {
        course.setNotebook(true);
        course.setPublic(false);
        course.setUser(learner);
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        when(chatSessionRepository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var result = service.createSession(7L, new ChatSessionCreateRequest());
        assertEquals(7L, result.getCourseId());
        verifyNoInteractions(courseEnrollmentRepository);
    }

    @Test
    void notebookChatRejectsNonOwnerEvenIfAnEnrollmentExists() {
        course.setNotebook(true);
        when(userRepository.findByEmail(learner.getEmail())).thenReturn(Optional.of(learner));
        when(courseRepository.findById(7L)).thenReturn(Optional.of(course));
        // No enrollment lookup is allowed to grant access to a private notebook.
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.createSession(7L, new ChatSessionCreateRequest())).getErrorCode());
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.getMySessions(7L, 0, 10)).getErrorCode());
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.getMessages(7L, 10L, 0, 10)).getErrorCode());
        assertEquals(ErrorCode.COURSE_ACCESS_DENIED, assertThrows(AppException.class,
                () -> service.beginTurn(7L, 10L, ChatRequest.builder().message("Read my document").build())).getErrorCode());
        verifyNoInteractions(courseEnrollmentRepository, chatSessionRepository, chatMessageRepository);
    }

}
