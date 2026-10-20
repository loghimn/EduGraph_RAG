package fu.coreservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiChatRequest;
import fu.coreservice.dto.ai.AiChatHistoryMessage;
import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.entity.ChatMessageStatus;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.dto.chat.ChatSource;
import fu.coreservice.dto.chat.ChatTurn;
import fu.coreservice.service.impl.ChatMessageServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.mockito.ArgumentCaptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatMessageServiceTest {
    private final ChatSessionService storage = mock(ChatSessionService.class);
    private final AiCoreClient client = mock(AiCoreClient.class);
    private final ClientHttpResponse response = mock(ClientHttpResponse.class);
    private final ChatTurn turn = new ChatTurn(10L, 2L, 20L, 21L,
            List.of(new AiChatHistoryMessage("assistant", "Learn Java and HTTP for Java Servlet")),
            List.of(new AiChatHistoryMessage("assistant", "An older selected answer about HTTP")));
    private final ChatRequest request = ChatRequest.builder().message("Kế thừa?")
            .conceptIds(List.of("concept1")).build();
    private final ChatMessageService service = new ChatMessageServiceImpl(storage, client, new ObjectMapper());

    @BeforeEach
    void setup() {
        when(storage.beginTurn(7L, 10L, request)).thenReturn(turn);
    }

    @AfterEach
    void resetRequestContext() { RequestContextHolder.resetRequestAttributes(); }

    private MockHttpServletRequest mvcRequest() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));
        return servletRequest;
    }

    private CallableProcessingInterceptor lifecycle(MockHttpServletRequest servletRequest) {
        return WebAsyncUtils.getAsyncManager(servletRequest)
                .getCallableInterceptor(ChatMessageServiceImpl.ASYNC_INTERCEPTOR_KEY);
    }

    @Test
    void mvcTimeoutBeforeWorkerStartsPersistsFailedAndDoesNotCallPython() throws Exception {
        var servletRequest = mvcRequest();
        var body = service.sendMessage(7L, 10L, request);
        var interceptor = lifecycle(servletRequest);
        assertNotNull(interceptor);
        interceptor.handleTimeout(new ServletWebRequest(servletRequest), () -> null);
        body.writeTo(new ByteArrayOutputStream());
        interceptor.afterCompletion(new ServletWebRequest(servletRequest), () -> null);
        verifyNoInteractions(client);
        verify(storage).finishTurn(eq(turn), eq(""), eq(ChatMessageStatus.FAILED),
                eq("MVC async streaming timeout"), anyInt(), eq(List.of()), eq(false));
    }

    @Test
    void timeoutClosesUpstreamAndKeepsPartialAnswerWithoutDoubleFinalization() throws Exception {
        var servletRequest = mvcRequest();
        BlockingBody input = new BlockingBody();
        when(client.openChatStream(any())).thenReturn(response);
        when(response.getBody()).thenReturn(input);
        doAnswer(call -> { input.release.countDown(); return null; }).when(response).close();
        StreamingResponseBody body = service.sendMessage(7L, 10L, request);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Thread worker = Thread.startVirtualThread(() -> {
            try { body.writeTo(new ByteArrayOutputStream()); finished.complete(null); }
            catch (Throwable e) { finished.completeExceptionally(e); }
        });
        try {
            assertTrue(input.waiting.await(5, TimeUnit.SECONDS));
            lifecycle(servletRequest).handleTimeout(new ServletWebRequest(servletRequest), () -> null);
            finished.get(5, TimeUnit.SECONDS);
            lifecycle(servletRequest).afterCompletion(new ServletWebRequest(servletRequest), () -> null);
            verify(storage).finishTurn(eq(turn), eq("Partial"), eq(ChatMessageStatus.FAILED),
                    eq("MVC async streaming timeout"), anyInt(), eq(List.of()), eq(false));
            verify(response).close();
        } finally {
            input.release.countDown();
            worker.interrupt();
        }
    }

    @Test
    void springInterruptBeforeTimeoutCallbackDoesNotMisclassifyTheTurn() throws Exception {
        var servletRequest = mvcRequest();
        BlockingBody input = new BlockingBody();
        when(client.openChatStream(any())).thenReturn(response);
        when(response.getBody()).thenReturn(input);
        StreamingResponseBody body = service.sendMessage(7L, 10L, request);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        Thread worker = Thread.startVirtualThread(() -> {
            try { body.writeTo(new ByteArrayOutputStream()); finished.complete(null); }
            catch (Throwable e) { finished.completeExceptionally(e); }
        });
        try {
            assertTrue(input.waiting.await(5, TimeUnit.SECONDS));
            worker.interrupt(); // MVC Future.cancel(true) happens before handleTimeout.
            finished.get(5, TimeUnit.SECONDS);
            verify(storage, never()).finishTurn(any(), any(), any(), any(), anyInt(), any(), anyBoolean());
            lifecycle(servletRequest).handleTimeout(new ServletWebRequest(servletRequest), () -> null);
            verify(storage).finishTurn(eq(turn), eq("Partial"), eq(ChatMessageStatus.FAILED),
                    eq("MVC async streaming timeout"), anyInt(), eq(List.of()), eq(false));
        } finally {
            input.release.countDown();
            worker.interrupt();
        }
    }

    @Test
    void doneWinnerIsNotOverwrittenByLaterTimeoutOrCompletion() throws Exception {
        var servletRequest = mvcRequest();
        run("event: token\ndata: {\"token\":\"Answer\"}\n\n"
                + "event: sources\ndata: []\n\nevent: done\ndata: {}\n\n");
        var interceptor = lifecycle(servletRequest);
        interceptor.handleTimeout(new ServletWebRequest(servletRequest), () -> null);
        interceptor.handleError(new ServletWebRequest(servletRequest), () -> null, new IOException("late error"));
        interceptor.afterCompletion(new ServletWebRequest(servletRequest), () -> null);
        verify(storage).finishTurn(eq(turn), eq("Answer"), eq(ChatMessageStatus.COMPLETED),
                isNull(), anyInt(), eq(List.of()), eq(false));
    }

    private static class BlockingBody extends InputStream {
        final ByteArrayInputStream prefix = new ByteArrayInputStream(
                "event: token\ndata: {\"token\":\"Partial\"}\n\n".getBytes(StandardCharsets.UTF_8));
        final CountDownLatch waiting = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (prefix.available() > 0) return prefix.read(b, off, len);
            waiting.countDown();
            try { release.await(); return -1; }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
        }
        @Override public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) == -1 ? -1 : b[0] & 255;
        }
        @Override public void close() { release.countDown(); }
    }

    private ByteArrayOutputStream run(String events) throws Exception {
        when(client.openChatStream(any())).thenReturn(response);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(events.getBytes(StandardCharsets.UTF_8)));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.sendMessage(7L, 10L, request).writeTo(output);
        return output;
    }

    @Test
    void persistsVietnameseAnswerAndMappedSourcesBeforeDoneWithoutAsyncSecurityContext() throws Exception {
        AtomicBoolean persisted = new AtomicBoolean();
        List<ChatSource> expected = List.of(new ChatSource(1, 40L, "chunk1", 3, 0, "Nguồn"));
        doAnswer(call -> { persisted.set(true); return null; }).when(storage).finishTurn(eq(turn),
                eq("Kế thừa [1]"), eq(ChatMessageStatus.COMPLETED), isNull(), anyInt(), eq(expected), eq(false));
        when(client.openChatStream(any())).thenReturn(response);
        byte[] bytes = ("event: token\r\ndata: {\"token\":\"Kế thừa [1]\"}\r\n\r\n"
                + "event: sources\r\ndata: [{\"citation\":1,\"doc_id\":\"40\",\"chunk_id\":\"chunk1\","
                + "\"page\":3,\"index\":0,\"text\":\"Nguồn\"}]\r\n\r\n"
                + "event: done\r\ndata: {}\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        });
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            @Override public void write(byte[] b) throws IOException {
                if (new String(b, StandardCharsets.UTF_8).contains("event: done")) assertTrue(persisted.get());
                super.write(b);
            }
        };
        service.sendMessage(7L, 10L, request).writeTo(output);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("Kế thừa [1]"));
        assertTrue(persisted.get());
        ArgumentCaptor<AiChatRequest> upstream = ArgumentCaptor.forClass(AiChatRequest.class);
        verify(client).openChatStream(upstream.capture());
        assertEquals("2", upstream.getValue().getUserId());
        assertEquals("7", upstream.getValue().getCourseId());
        assertEquals(List.of("concept1"), upstream.getValue().getConceptIds());
        assertEquals(turn.history(), upstream.getValue().getHistory());
        assertEquals(turn.replyContext(), upstream.getValue().getReplyContext());
        verify(response).close();
    }

    @Test
    void noDataNoticeIsPersistedAsCompletedBotWithoutCitations() throws Exception {
        String message = "Không tìm thấy dữ liệu phù hợp với câu hỏi trong tài liệu của khóa học.";
        var output = run("event: token\ndata: {\"token\":\"" + message + "\"}\n\n"
                + "event: sources\ndata: []\n\nevent: done\ndata: {}\n\n");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains(message));
        verify(storage).finishTurn(eq(turn), eq(message), eq(ChatMessageStatus.COMPLETED),
                isNull(), anyInt(), eq(List.of()), eq(false));
    }

    @Test
    void clarificationIsForwardedAndRemovesTemporaryBot() throws Exception {
        var output = run("event: clarify\ndata: {\"candidates\":[{\"id\":\"a\",\"name\":\"A\"}]}\n\n"
                + "event: done\ndata: {}\n\n");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: clarify"));
        verify(storage).finishTurn(eq(turn), eq(""), any(), isNull(), anyInt(), eq(List.of()), eq(true));
        verify(response).close();
    }

    @Test
    void upstreamErrorRetainsPartialAnswerAsFailed() throws Exception {
        run("event: token\ndata: {\"token\":\"Partial\"}\n\n"
                + "event: error\ndata: {\"error\":\"Provider failed\"}\n\n"
                + "event: done\ndata: {}\n\n");
        verify(storage).finishTurn(eq(turn), eq("Partial"), eq(ChatMessageStatus.FAILED),
                eq("Provider failed"), anyInt(), eq(List.of()), eq(false));
    }

    @Test
    void prematureEofIsFailedInsteadOfCompleted() throws Exception {
        var output = run("event: token\ndata: {\"token\":\"Partial\"}\n\n");
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: error"));
        verify(storage).finishTurn(eq(turn), eq("Partial"), eq(ChatMessageStatus.FAILED),
                contains("before done"), anyInt(), eq(List.of()), eq(false));
        verify(response).close();
    }

    @Test
    void malformedSourcesFailWithoutSavingPartialCitationList() throws Exception {
        run("event: token\ndata: {\"token\":\"Answer\"}\n\n"
                + "event: sources\ndata: [{\"citation\":1,\"chunk_id\":\"a\"},"
                + "{\"citation\":1,\"chunk_id\":\"b\"}]\n\n");
        verify(storage).finishTurn(eq(turn), eq("Answer"), eq(ChatMessageStatus.FAILED),
                eq("Invalid citation metadata"), anyInt(), eq(List.of()), eq(false));
    }

    @Test
    void doneWithoutSourcesDoesNotClaimSuccess() throws Exception {
        run("event: token\ndata: {\"token\":\"Answer\"}\n\nevent: done\ndata: {}\n\n");
        verify(storage).finishTurn(eq(turn), eq("Answer"), eq(ChatMessageStatus.FAILED),
                contains("without sources"), anyInt(), eq(List.of()), eq(false));
    }

    @Test
    void openingPythonFailureMarksBotFailedAndReturnsSseError() throws Exception {
        when(client.openChatStream(any())).thenThrow(new AppException(ErrorCode.AI_CORE_UNAVAILABLE));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        service.sendMessage(7L, 10L, request).writeTo(output);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("event: done"));
        verify(storage).finishTurn(eq(turn), eq(""), eq(ChatMessageStatus.FAILED),
                anyString(), anyInt(), eq(List.of()), eq(false));
    }

    @Test
    void clientDisconnectClosesPythonAndRetainsInterruptedAnswer() throws Exception {
        when(client.openChatStream(any())).thenReturn(response);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(
                "event: token\ndata: {\"token\":\"Partial\"}\n\n".getBytes(StandardCharsets.UTF_8)));
        service.sendMessage(7L, 10L, request).writeTo(new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Disconnected"); }
        });
        verify(storage).finishTurn(eq(turn), eq("Partial"), eq(ChatMessageStatus.INTERRUPTED),
                eq("Response stream unavailable"), anyInt(), eq(List.of()), eq(false));
        verify(response).close();
    }

    @Test
    void accessDeniedBeforeStreamingDoesNotContactPython() {
        when(storage.beginTurn(7L, 10L, request)).thenThrow(new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        assertThrows(AppException.class, () -> service.sendMessage(7L, 10L, request));
        verifyNoInteractions(client);
    }
}
