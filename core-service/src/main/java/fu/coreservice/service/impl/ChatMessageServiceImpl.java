package fu.coreservice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiChatRequest;
import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.entity.ChatMessageStatus;
import fu.coreservice.service.ChatMessageService;
import fu.coreservice.service.ChatSessionService;
import fu.coreservice.dto.chat.ChatSource;
import fu.coreservice.dto.chat.ChatTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncUtils;
import java.util.concurrent.Callable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatMessageServiceImpl implements ChatMessageService {
    public static final String ASYNC_INTERCEPTOR_KEY = ChatMessageServiceImpl.class.getName() + ".stream";
    private final ChatSessionService chatSessionService;
    private final AiCoreClient aiCoreClient;
    private final ObjectMapper objectMapper;

    @Override
    public StreamingResponseBody sendMessage(Long courseId, Long sessionId, ChatRequest request) {
        ChatTurn turn = chatSessionService.beginTurn(courseId, sessionId, request);
        AiChatRequest aiRequest = AiChatRequest.builder().courseId(courseId.toString())
                .userId(turn.userId().toString()).message(request.getMessage())
                .history(turn.history())
                .replyContext(turn.replyContext())
                .conceptIds(request.getConceptIds() == null ? null : new ArrayList<>(request.getConceptIds())).build();
        StreamExecution execution = new StreamExecution(turn);
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            execution.mvcManaged = true;
            WebAsyncUtils.getAsyncManager(attributes.getRequest())
                    .registerCallableInterceptor(ASYNC_INTERCEPTOR_KEY, execution);
        }
        return output -> stream(execution, aiRequest, output);
    }

    private void stream(StreamExecution execution, AiChatRequest request, OutputStream output) {
        if (!execution.start()) return;
        try {
            ClientHttpResponse response = aiCoreClient.openChatStream(request);
            if (!execution.attach(response)) return;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.getBody(), StandardCharsets.UTF_8))) {
                String event = "message";
                StringBuilder data = new StringBuilder();
                String line;
                while (!execution.isStopped() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.isEmpty()) {
                            event = "message";
                            continue;
                        }
                        String payload = data.substring(0, data.length() - 1);
                        if ("done".equals(event)) {
                            if (execution.complete()) writeEvent(output, event, payload);
                            return;
                        }
                        if (!execution.accept(event, payload)) return;
                        writeEvent(output, event, payload);
                        event = "message";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = fieldValue(line);
                    } else if (line.startsWith("data:")) {
                        data.append(fieldValue(line)).append('\n');
                    } else if (line.startsWith(":")) {
                        writeBytes(output, (line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (!execution.isStopped()) throw new IOException("AI stream closed before done");
            }
        } catch (ClientDisconnectedException e) {
            if (execution.deferInterruptedWorker()) return;
            execution.terminate(ChatMessageStatus.INTERRUPTED, "Response stream unavailable");
        } catch (Exception e) {
            if (execution.deferInterruptedWorker()) return;
            if (execution.isStopped()) return;
            log.warn("Chat stream failed. sessionId={}, reason={}", execution.turn.sessionId(), e.getMessage());
            execution.terminate(ChatMessageStatus.FAILED, e.getMessage());
            try {
                writeEvent(output, "error", "{\"error\":\"Unable to complete the answer. Please try again.\"}");
                writeEvent(output, "done", "{}");
            } catch (IOException deliveryError) {
                log.info("Could not deliver chat error. sessionId={}", execution.turn.sessionId());
            }
        } finally {
            execution.closeUpstream();
            execution.finishIfNeeded();
        }
    }

    /** One request-scoped owner for state, upstream resources and terminal persistence. */
    private class StreamExecution implements CallableProcessingInterceptor {
        private final ChatTurn turn;
        private final StreamState state = new StreamState();
        private final long started = System.nanoTime();
        private ClientHttpResponse upstream;
        private Thread worker;
        private boolean stopped;
        private boolean finalized;
        private boolean mvcManaged;
        private boolean awaitingContainer;

        StreamExecution(ChatTurn turn) { this.turn = turn; }

        synchronized boolean start() {
            if (stopped || finalized) return false;
            worker = Thread.currentThread();
            return true;
        }

        boolean attach(ClientHttpResponse response) {
            synchronized (this) {
                if (!stopped && !finalized) {
                    upstream = response;
                    return true;
                }
            }
            response.close(); // A response arriving after timeout must never be streamed.
            return false;
        }

        synchronized boolean isStopped() { return stopped || finalized; }

        synchronized boolean accept(String event, String payload) throws IOException {
            if (stopped || finalized) return false;
            acceptEvent(state, event, payload);
            return true;
        }

        synchronized boolean complete() throws IOException {
            if (deferInterruptedWorker()) return false;
            if (stopped || finalized) return false;
            if (!state.failed && !state.clarification && !state.sourcesReceived) {
                throw new IOException("AI stream ended without sources");
            }
            persist();
            return true;
        }

        void terminate(ChatMessageStatus status, String reason) {
            Thread running;
            synchronized (this) {
                if (stopped || finalized) return;
                stopped = true;
                state.status = status;
                state.error = reason;
                state.failed = status == ChatMessageStatus.FAILED;
                // Only a successful clarify/done sequence removes the BOT placeholder.
                state.clarification = false;
                running = worker;
            }
            if (running != null && running != Thread.currentThread()) running.interrupt();
            try {
                finishIfNeeded();
            } finally {
                closeUpstream();
            }
        }

        void closeUpstream() {
            ClientHttpResponse response;
            synchronized (this) {
                response = upstream;
                upstream = null;
            }
            if (response != null) {
                try {
                    response.close();
                } catch (RuntimeException e) {
                    log.warn("Could not close AI stream. sessionId={}", turn.sessionId());
                }
            }
        }

        synchronized void finishIfNeeded() {
            if (!finalized && !(awaitingContainer && !stopped)) persist();
        }

        synchronized boolean deferInterruptedWorker() {
            // MVC cancels/interupts its Future BEFORE invoking handleTimeout or
            // handleError. Let that callback select the actual terminal cause.
            if (mvcManaged && !stopped && !finalized && Thread.currentThread().isInterrupted()) {
                awaitingContainer = true;
                return true;
            }
            return false;
        }

        private void persist() {
            int latency = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
            chatSessionService.finishTurn(turn, state.content.toString(), state.status, state.error,
                    latency, List.copyOf(state.sources), state.clarification);
            finalized = true; // Set only after the transactional call has committed.
        }

        @Override
        public <T> Object handleTimeout(NativeWebRequest request, Callable<T> task) {
            terminate(ChatMessageStatus.FAILED, "MVC async streaming timeout");
            return RESULT_NONE; // Let MVC complete its normal timeout dispatch.
        }

        @Override
        public <T> Object handleError(NativeWebRequest request, Callable<T> task, Throwable error) {
            terminate(ChatMessageStatus.INTERRUPTED, "Response stream unavailable");
            return RESULT_NONE;
        }

        @Override
        public <T> void afterCompletion(NativeWebRequest request, Callable<T> task) {
            terminate(ChatMessageStatus.INTERRUPTED, "Async request completed before stream finished");
        }
    }

    private void acceptEvent(StreamState state, String event, String payload) throws IOException {
        JsonNode json = objectMapper.readTree(payload);
        if (json == null) {
            throw new IOException("Empty AI event");
        }
        switch (event) {
            case "token" -> {
                if (state.failed || state.clarification || !json.path("token").isTextual()) {
                    throw new IOException("Invalid token event");
                }
                state.content.append(json.get("token").textValue());
            }
            case "sources" -> {
                if (state.failed || state.clarification || state.sourcesReceived || !json.isArray()) {
                    throw new IOException("Invalid sources event");
                }
                List<ChatSource> sources = new ArrayList<>();
                Set<Integer> numbers = new HashSet<>();
                for (JsonNode source : json) {
                    JsonNode citation = source.path("citation");
                    String chunkId = source.path("chunk_id").asText("");
                    if (!citation.isIntegralNumber() || !citation.canConvertToInt()
                            || citation.intValue() < 1 || !numbers.add(citation.intValue())
                            || chunkId.isBlank() || chunkId.length() > 100) {
                        throw new IOException("Invalid citation metadata");
                    }
                    JsonNode doc = source.get("doc_id");
                    Long documentId;
                    try {
                        documentId = doc == null || doc.isNull() ? null : Long.valueOf(doc.asText());
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid source document ID", e);
                    }
                    sources.add(new ChatSource(citation.intValue(), documentId, chunkId,
                            optionalInteger(source, "page"), optionalInteger(source, "index"),
                            source.path("text").asText("")));
                }
                state.sources = sources;
                state.sourcesReceived = true;
            }
            case "clarify" -> {
                if (!state.content.isEmpty() || state.sourcesReceived || state.failed
                        || !json.path("candidates").isArray()) {
                    throw new IOException("Invalid clarification event");
                }
                state.clarification = true;
            }
            case "error" -> {
                state.failed = true;
                state.status = ChatMessageStatus.FAILED;
                state.error = json.path("error").asText("AI generation failed");
            }
            default -> throw new IOException("Unsupported AI event: " + event);
        }
    }

    private Integer optionalInteger(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IOException("Invalid source " + field);
        }
        return value.intValue();
    }

    private String fieldValue(String line) {
        String value = line.substring(line.indexOf(':') + 1);
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private void writeEvent(OutputStream output, String event, String payload) throws ClientDisconnectedException {
        StringBuilder frame = new StringBuilder("event: ").append(event).append('\n');
        for (String line : payload.split("\n", -1)) {
            frame.append("data: ").append(line).append('\n');
        }
        frame.append('\n');
        writeBytes(output, frame.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(OutputStream output, byte[] data) throws ClientDisconnectedException {
        try {
            output.write(data);
            output.flush();
        } catch (IOException e) {
            throw new ClientDisconnectedException(e);
        }
    }

    private static class ClientDisconnectedException extends IOException {
        ClientDisconnectedException(IOException cause) { super(cause); }
    }

    private static class StreamState {
        final StringBuilder content = new StringBuilder();
        List<ChatSource> sources = List.of();
        ChatMessageStatus status = ChatMessageStatus.COMPLETED;
        String error;
        boolean failed;
        boolean clarification;
        boolean sourcesReceived;
    }
}
