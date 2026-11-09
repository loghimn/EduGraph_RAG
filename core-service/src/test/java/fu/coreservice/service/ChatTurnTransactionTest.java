package fu.coreservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.chat.ChatRequest;
import fu.coreservice.dto.chat.ChatSource;
import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import fu.coreservice.service.impl.ChatMessageServiceImpl;
import fu.coreservice.service.impl.ChatSessionServiceImpl;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.client.ClientHttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.*;

class ChatTurnTransactionTest {
    private SessionFactory factory;
    private ChatMessageService service;
    private ChatSessionService storage;
    private CourseRepository courses;
    private ChatMessageRepository messages;
    private AiCoreClient ai;
    private Long sessionId;
    private static final String EMAIL = "chat@test.local";

    @BeforeEach
    void setup() {
        factory = new Configuration().addAnnotatedClass(User.class).addAnnotatedClass(Course.class)
                .addAnnotatedClass(CourseEnrollment.class).addAnnotatedClass(Document.class)
                .addAnnotatedClass(LearningPathPlan.class).addAnnotatedClass(LearningPathGoal.class)
                .addAnnotatedClass(LearningPathStep.class).addAnnotatedClass(ChatSession.class)
                .addAnnotatedClass(ChatMessage.class).addAnnotatedClass(MessageCitation.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true").buildSessionFactory();
        factory.inTransaction(session -> {
            User user = User.builder().email(EMAIL).username("chat-user").passwordHash("test")
                    .role(UserRole.STUDENT).build();
            session.persist(user);
            ChatSession chat = ChatSession.builder().learner(user).title("Global chat").build();
            session.persist(chat);
            sessionId = chat.getSessionId();
        });
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(factory));
        courses = mock(CourseRepository.class, delegatesTo(repositories.getRepository(CourseRepository.class)));
        messages = mock(ChatMessageRepository.class,
                delegatesTo(repositories.getRepository(ChatMessageRepository.class)));
        var manager = new JpaTransactionManager(factory);
        storage = transactional(new ChatSessionServiceImpl(
                repositories.getRepository(UserRepository.class), courses,
                repositories.getRepository(CourseEnrollmentRepository.class),
                repositories.getRepository(ChatSessionRepository.class), messages,
                repositories.getRepository(MessageCitationRepository.class)), ChatSessionService.class, manager);
        ai = mock(AiCoreClient.class);
        service = transactional(new ChatMessageServiceImpl(storage, ai, new ObjectMapper()),
                ChatMessageService.class, manager);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(EMAIL, null, List.of()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        if (factory != null) factory.close();
    }

    @Test
    void scopeLookupFailureRollsBackAlreadyFlushedUserAndBotMessages() {
        var failure = new IllegalStateException("scope lookup failed");
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(2, messages.count());
            assertEquals(1, messages.findBySenderType(SenderType.BOT).size());
            assertEquals(ChatMessageStatus.STREAMING,
                    messages.findBySenderType(SenderType.BOT).getFirst().getStatus());
            throw failure;
        }).when(courses).findGlobalChatCourseIds();

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service.sendMessage(null, sessionId, ChatRequest.builder().message("Stack?").build())));

        assertEquals(0, messages.count());
        verifyNoInteractions(ai);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void successfulPreparationCommitsBeforeReturningStreamCallback() {
        doReturn(List.of(26L, 28L)).when(courses).findGlobalChatCourseIds();
        var callback = service.sendMessage(null, sessionId, ChatRequest.builder().message("Stack?").build());
        assertNotNull(callback);
        assertEquals(2, messages.count());
        verifyNoInteractions(ai);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Test
    void committedAnswerSurvivesFailureRetryAndRemainsAvailableForRepliesAndContext() {
        var turn = storage.beginGlobalTurn(sessionId, ChatRequest.builder().message("Stack?").build());
        storage.finishTurn(turn, "Original [1]", ChatMessageStatus.COMPLETED, null, 100,
                List.of(new ChatSource(1, 40L, "chunk1", 1, 0, "Source")), false);
        // Simulate a lost acknowledgment followed by the stream's failure retry.
        storage.finishTurn(turn, "Replacement", ChatMessageStatus.FAILED, "Commit uncertain", 999, List.of(), false);
        storage.finishTurn(turn, "", ChatMessageStatus.COMPLETED, null, 999, List.of(), true);

        var answer = messages.findById(turn.assistantMessageId()).orElseThrow();
        assertEquals(ChatMessageStatus.COMPLETED, answer.getStatus());
        assertEquals("Original [1]", answer.getContent());
        assertNull(answer.getErrorMessage());
        assertEquals(100, answer.getLatencyMs());
        var history = storage.getGlobalMessages(sessionId, 0, 20);
        var savedAnswer = history.getContent().stream()
                .filter(message -> message.getMessageId().equals(turn.assistantMessageId())).findFirst().orElseThrow();
        assertEquals(40L, savedAnswer.getCitations().getFirst().getDocumentId());
        var nextTurn = storage.beginGlobalTurn(sessionId, ChatRequest.builder().message("Explain further")
                .replyToMessageId(turn.assistantMessageId()).build());
        assertTrue(nextTurn.history().stream().anyMatch(message -> message.content().equals("Original [1]")));
        assertEquals("Original [1]", nextTurn.replyContext().getLast().content());
    }

    @Test
    void clarificationReceiptRollsBackIfDeletingPlaceholderFails() {
        var turn = storage.beginGlobalTurn(sessionId, ChatRequest.builder().message("Stack?").build());
        doThrow(new IllegalStateException("delete failed")).when(messages).delete(any(ChatMessage.class));
        assertThrows(IllegalStateException.class, () -> storage.finishTurn(turn, "", ChatMessageStatus.COMPLETED,
                null, 100, List.of(), true));
        assertNull(messages.findById(turn.userMessageId()).orElseThrow().getFinalizedClarificationAnswerId());
        assertEquals(ChatMessageStatus.STREAMING,
                messages.findById(turn.assistantMessageId()).orElseThrow().getStatus());
    }

    @Test
    void lostClarificationCommitAcknowledgmentStillEndsStreamWithDone() throws Exception {
        ChatSessionService uncertainStorage = mock(ChatSessionService.class, delegatesTo(storage));
        AtomicBoolean lostAcknowledgment = new AtomicBoolean();
        doAnswer(call -> {
            storage.finishTurn(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                    call.getArgument(3), call.getArgument(4), call.getArgument(5), call.getArgument(6));
            if (Boolean.TRUE.equals(call.getArgument(6)) && lostAcknowledgment.compareAndSet(false, true)) {
                throw new IllegalStateException("Commit acknowledgment lost");
            }
            return null;
        }).when(uncertainStorage).finishTurn(any(), any(), any(), any(), anyInt(), any(), anyBoolean());
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(ai.openChatStream(any())).thenReturn(response);
        when(response.getBody()).thenReturn(new ByteArrayInputStream((
                "event: clarify\ndata: {\"candidates\":[{\"id\":\"a\",\"name\":\"A\"}]}\n\n"
                        + "event: done\ndata: {}\n\n").getBytes(StandardCharsets.UTF_8)));
        ChatMessageService uncertainService = transactional(
                new ChatMessageServiceImpl(uncertainStorage, ai, new ObjectMapper()), ChatMessageService.class,
                new JpaTransactionManager(factory));
        var callback = uncertainService.sendMessage(null, sessionId, ChatRequest.builder().message("Stack?").build());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertDoesNotThrow(() -> callback.writeTo(output));
        String events = output.toString(StandardCharsets.UTF_8);
        assertTrue(lostAcknowledgment.get());
        assertTrue(events.contains("event: clarify\n"));
        assertTrue(events.endsWith("event: done\ndata: {}\n\n"));
        assertEquals(1, events.split("event: done", -1).length - 1);
        assertEquals(0, messages.findBySenderType(SenderType.BOT).size());
        var question = messages.findBySenderType(SenderType.USER).getFirst();
        assertNotNull(question.getFinalizedClarificationAnswerId());
        assertEquals(1, storage.getGlobalMessages(sessionId, 0, 20).getTotalElements());
        verify(uncertainStorage, times(2)).finishTurn(any(), any(), any(), any(), anyInt(), any(), anyBoolean());
        verify(response).close();
    }

    private <T> T transactional(T target, Class<T> type, JpaTransactionManager manager) {
        var proxy = new ProxyFactory(target);
        proxy.setInterfaces(type);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
}
