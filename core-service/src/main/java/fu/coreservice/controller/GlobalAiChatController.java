package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.ChatMessageResponse;
import fu.coreservice.dto.chat.ChatSessionCreateRequest;
import fu.coreservice.dto.chat.ChatSessionResponse;
import fu.coreservice.dto.chat.ChatRequest;
import fu.coreservice.dto.chat.MessageFeedbackRequest;
import fu.coreservice.dto.chat.MessageFeedbackResponse;
import fu.coreservice.service.ChatSessionService;
import fu.coreservice.service.ChatMessageService;
import fu.coreservice.service.MessageFeedbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Global chat endpoints — no course restriction.
 * Used by the Python AI core when course_id is null (full graph chat).
 */
@RestController
@RequestMapping("/api/chat")
@SecurityRequirements({
        @SecurityRequirement(name = "bearerAuth"),
        @SecurityRequirement(name = "googleOAuth2")
})
@RequiredArgsConstructor
public class GlobalAiChatController {

    private static final MediaType SSE_MEDIA_TYPE = MediaType.valueOf("text/event-stream");

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final MessageFeedbackService messageFeedbackService;

    @Operation(summary = "Create a global chat session (no course)")
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<ChatSessionResponse>> createGlobalSession(
            @Valid @RequestBody ChatSessionCreateRequest request) {
        ChatSessionResponse response = chatSessionService.createGlobalSession(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.<ChatSessionResponse>builder()
                        .success(true).message("Global chat session created").data(response).build());
    }

    @Operation(summary = "List my global chat sessions")
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<PageResponse<ChatSessionResponse>>> getGlobalSessions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResponse<ChatSessionResponse> response = chatSessionService.getGlobalSessions(page, size);
        return ResponseEntity.ok(ApiResponse.<PageResponse<ChatSessionResponse>>builder()
                .success(true).message("Sessions retrieved").data(response).build());
    }

    @Operation(summary = "Send a message in a global chat session")
    @PostMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<StreamingResponseBody> sendMessage(
            @PathVariable Long sessionId,
            @Valid @RequestBody ChatRequest request) {
        StreamingResponseBody stream = chatMessageService.sendMessage(null, sessionId, request);
        return ResponseEntity.ok().contentType(SSE_MEDIA_TYPE)
                .cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(stream);
    }

    @Operation(summary = "Get chat message history")
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<PageResponse<ChatMessageResponse>>> getMessages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<ChatMessageResponse> response = chatSessionService.getGlobalMessages(sessionId, page, size);
        return ResponseEntity.ok(ApiResponse.<PageResponse<ChatMessageResponse>>builder()
                .success(true).message("Messages retrieved").data(response).build());
    }

    @Operation(summary = "Create or update message feedback")
    @PutMapping("/sessions/{sessionId}/messages/{messageId}/feedback")
    public ResponseEntity<ApiResponse<MessageFeedbackResponse>> saveFeedback(
            @PathVariable Long sessionId, @PathVariable Long messageId,
            @Valid @RequestBody MessageFeedbackRequest request) {
        MessageFeedbackResponse response = messageFeedbackService.saveFeedback(null, sessionId, messageId, request);
        return ResponseEntity.ok(ApiResponse.<MessageFeedbackResponse>builder()
                .success(true).message("Feedback saved").data(response).build());
    }

    @Operation(summary = "Get message feedback")
    @GetMapping("/sessions/{sessionId}/messages/{messageId}/feedback")
    public ResponseEntity<ApiResponse<MessageFeedbackResponse>> getFeedback(
            @PathVariable Long sessionId, @PathVariable Long messageId) {
        MessageFeedbackResponse response = messageFeedbackService.getFeedback(null, sessionId, messageId);
        return ResponseEntity.ok(ApiResponse.<MessageFeedbackResponse>builder()
                .success(true).data(response).build());
    }

    @Operation(summary = "Delete message feedback")
    @DeleteMapping("/sessions/{sessionId}/messages/{messageId}/feedback")
    public ResponseEntity<ApiResponse<Void>> deleteFeedback(
            @PathVariable Long sessionId, @PathVariable Long messageId) {
        messageFeedbackService.deleteFeedback(null, sessionId, messageId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true).message("Feedback removed").build());
    }
}