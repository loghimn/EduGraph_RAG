package fu.coreservice.controller;

import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.ChatMessageResponse;
import fu.coreservice.dto.chat.ChatSessionCreateRequest;
import fu.coreservice.dto.chat.ChatSessionResponse;
import fu.coreservice.service.ChatSessionService;
import fu.coreservice.service.ChatMessageService;
import fu.coreservice.service.MessageFeedbackService;
import fu.coreservice.dto.chat.MessageFeedbackRequest;
import fu.coreservice.dto.chat.MessageFeedbackResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;


/**
 * Course-scoped private chat sessions, history and persisted SSE messages.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/chat")
@SecurityRequirements({
        @SecurityRequirement(name = "bearerAuth"),
        @SecurityRequirement(name = "googleOAuth2")
})
@RequiredArgsConstructor
public class AiChatController {

    private static final MediaType SSE_MEDIA_TYPE = MediaType.valueOf("text/event-stream");

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final MessageFeedbackService messageFeedbackService;

    @Operation(summary = "Create or update message feedback",
            description = "Rates a completed BOT message in your own session: 1 helpful, -1 not helpful. "
                    + "Optional comment up to 2000 characters. Replaces the existing feedback; does not call AI.")
    @PutMapping("/sessions/{sessionId}/messages/{messageId}/feedback")
    public ResponseEntity<ApiResponse<MessageFeedbackResponse>> saveFeedback(
            @PathVariable Long courseId, @PathVariable Long sessionId, @PathVariable Long messageId,
            @Valid @RequestBody MessageFeedbackRequest request) {
        MessageFeedbackResponse response = messageFeedbackService.saveFeedback(courseId, sessionId, messageId, request);
        return ResponseEntity.ok(ApiResponse.<MessageFeedbackResponse>builder()
                .success(true).message("Message feedback saved successfully").data(response).build());
    }

    @Operation(summary = "Get message feedback",
            description = "Returns your feedback for a completed BOT message. Data is null when no feedback exists.")
    @GetMapping("/sessions/{sessionId}/messages/{messageId}/feedback")
    public ResponseEntity<ApiResponse<MessageFeedbackResponse>> getFeedback(
            @PathVariable Long courseId, @PathVariable Long sessionId, @PathVariable Long messageId) {
        MessageFeedbackResponse response = messageFeedbackService.getFeedback(courseId, sessionId, messageId);
        return ResponseEntity.ok(ApiResponse.<MessageFeedbackResponse>builder()
                .success(true).message("Message feedback retrieved successfully").data(response).build());
    }

    @Operation(summary = "Remove message feedback",
            description = "Removes only your feedback, preserving the chat message. Repeated deletion succeeds.")
    @DeleteMapping("/sessions/{sessionId}/messages/{messageId}/feedback")
    public ResponseEntity<ApiResponse<Void>> deleteFeedback(
            @PathVariable Long courseId, @PathVariable Long sessionId, @PathVariable Long messageId) {
        messageFeedbackService.deleteFeedback(courseId, sessionId, messageId);
        return ResponseEntity.ok(ApiResponse.<Void>builder().success(true)
                .message("Message feedback removed successfully").data(null).build());
    }

    @Operation(summary = "Send a message in my chat session",
            description = "Persists the question and streams the answer as token, sources, clarify, error and done events. "
                    + "Only the session owner with course access may send. The done event follows persistence. "
                    + "Optional replyToMessageId selects a completed message from this same session as primary context. "
                    + "Disconnecting cancels the upstream stream and retains a partial interrupted answer.")
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = "text/event-stream")
    public ResponseEntity<StreamingResponseBody> sendMessage(
            @PathVariable Long courseId, @PathVariable Long sessionId,
            @Valid @RequestBody ChatRequest request) {
        StreamingResponseBody stream = chatMessageService.sendMessage(courseId, sessionId, request);
        return ResponseEntity.ok().contentType(SSE_MEDIA_TYPE)
                .cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(stream);
    }

    @Operation(summary = "Create a chat session",
            description = "Creates a private conversation for the authenticated course owner or enrolled learner.")
    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<ChatSessionResponse>> createSession(
            @PathVariable Long courseId, @Valid @RequestBody ChatSessionCreateRequest request) {
        ChatSessionResponse response = chatSessionService.createSession(courseId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<ChatSessionResponse>builder()
                .success(true).message("Chat session created successfully").data(response).build());
    }

    @Operation(summary = "Get my chat sessions",
            description = "Lists only the authenticated user's conversations in this course, most recently active first. "
                    + "Page starts at 0; size must be between 1 and 100.")
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<PageResponse<ChatSessionResponse>>> getMySessions(
            @PathVariable Long courseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResponse<ChatSessionResponse> response = chatSessionService.getMySessions(courseId, page, size);
        return ResponseEntity.ok(ApiResponse.<PageResponse<ChatSessionResponse>>builder()
                .success(true).message("Chat sessions retrieved successfully").data(response).build());
    }

    @Operation(summary = "Get chat message history",
            description = "Returns messages and citations from the authenticated user's session, oldest first. "
                    + "Page starts at 0; size must be between 1 and 100. Other users' sessions return 404.")
    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<ApiResponse<PageResponse<ChatMessageResponse>>> getMessages(
            @PathVariable Long courseId, @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<ChatMessageResponse> response = chatSessionService.getMessages(courseId, sessionId, page, size);
        return ResponseEntity.ok(ApiResponse.<PageResponse<ChatMessageResponse>>builder()
                .success(true).message("Chat messages retrieved successfully").data(response).build());
    }

}
