package fu.coreservice.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiChatRequest;
import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.entity.ChatMessageStatus;
import fu.coreservice.service.ChatMessageService;
import fu.coreservice.service.ChatSessionService;
import fu.coreservice.service.chat.ChatSource;
import fu.coreservice.service.chat.ChatTurn;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

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
    private final ChatSessionService chatSessionService;
    private final AiCoreClient aiCoreClient;
    private final ObjectMapper objectMapper;

    @Override
    public StreamingResponseBody sendMessage(Long courseId, Long sessionId, ChatRequest request) {
        ChatTurn turn = chatSessionService.beginTurn(courseId, sessionId, request);
        AiChatRequest aiRequest = AiChatRequest.builder().courseId(courseId.toString())
                .userId(turn.userId().toString()).message(request.getMessage())
                .history(turn.history())
                .conceptIds(request.getConceptIds() == null ? null : new ArrayList<>(request.getConceptIds())).build();
        return output -> stream(turn, aiRequest, output);
    }

    private void stream(ChatTurn turn, AiChatRequest request, OutputStream output) {
        StreamState state = new StreamState();
        long started = System.nanoTime();
        boolean finalized = false;
        try {
            try (ClientHttpResponse response = aiCoreClient.openChatStream(request);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         response.getBody(), StandardCharsets.UTF_8))) {
                String event = "message";
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.isEmpty()) {
                            event = "message";
                            continue;
                        }
                        String payload = data.substring(0, data.length() - 1);
                        if ("done".equals(event)) {
                            if (!state.failed && !state.clarification && !state.sourcesReceived) {
                                throw new IOException("AI stream ended without sources");
                            }
                            finish(turn, state, started);
                            finalized = true;
                            writeEvent(output, event, payload);
                            return;
                        }
                        acceptEvent(state, event, payload);
                        writeEvent(output, event, payload);
                        event = "message";
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        event = fieldValue(line);
                    } else if (line.startsWith("data:")) {
                        data.append(fieldValue(line)).append('\n');
                    } else if (line.startsWith(":")) {
                        // Preserve heartbeats during a long upstream operation.
                        writeBytes(output, (line + "\n\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                throw new IOException("AI stream closed before done");
            }
        } catch (ClientDisconnectedException e) {
            if (finalized) return;
            state.status = ChatMessageStatus.INTERRUPTED;
            state.error = "Client disconnected";
            log.info("Chat client disconnected. sessionId={}", turn.sessionId());
        } catch (Exception e) {
            if (finalized) return;
            state.failed = true;
            state.status = ChatMessageStatus.FAILED;
            state.error = e.getMessage();
            log.warn("Chat stream failed. sessionId={}, reason={}", turn.sessionId(), e.getMessage());
            try {
                writeEvent(output, "error", "{\"error\":\"Unable to complete the answer. Please try again.\"}");
                // Finalize before done so clients can immediately reload persisted history.
                if (!finalized) {
                    finish(turn, state, started);
                    finalized = true;
                }
                writeEvent(output, "done", "{}");
            } catch (Exception deliveryError) {
                log.warn("Could not deliver chat error. sessionId={}", turn.sessionId());
            }
        } finally {
            if (!finalized) {
                finish(turn, state, started);
            }
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

    private void finish(ChatTurn turn, StreamState state, long started) {
        int latency = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000);
        chatSessionService.finishTurn(turn, state.content.toString(), state.status, state.error,
                latency, state.sources, state.clarification);
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
