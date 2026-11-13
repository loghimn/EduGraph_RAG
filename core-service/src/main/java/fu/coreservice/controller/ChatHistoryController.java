package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.*;
import fu.coreservice.service.ChatHistoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping({"/api/chat/sessions", "/api/courses/{courseId}/chat/sessions"})
public class ChatHistoryController {
    private final ChatHistoryService service;

    @GetMapping("/search")
    public ApiResponse<PageResponse<ChatSessionResponse>> search(@PathVariable(required = false) Long courseId,
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.search(courseId, q, page, size));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<ChatSessionResponse> get(@PathVariable(required = false) Long courseId, @PathVariable Long sessionId) {
        return ok(service.get(courseId, sessionId));
    }

    @PatchMapping("/{sessionId}")
    public ApiResponse<ChatSessionResponse> rename(@PathVariable(required = false) Long courseId, @PathVariable Long sessionId,
                                                  @Valid @RequestBody ChatSessionRenameRequest request) {
        return ok(service.rename(courseId, sessionId, request.title()));
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> delete(@PathVariable(required = false) Long courseId, @PathVariable Long sessionId) {
        service.delete(courseId, sessionId);
        return ok(null);
    }

    @GetMapping("/{sessionId}/messages/recent")
    public ApiResponse<ChatMessageWindow> recent(@PathVariable(required = false) Long courseId, @PathVariable Long sessionId,
            @RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "30") int size) {
        return ok(service.recent(courseId, sessionId, beforeId, size));
    }

    @GetMapping("/{sessionId}/citations/{citationId}/source")
    public ApiResponse<CitationSourceResponse> source(@PathVariable(required = false) Long courseId, @PathVariable Long sessionId,
                                                     @PathVariable Long citationId) {
        return ok(service.source(courseId, sessionId, citationId));
    }

    private <T> ApiResponse<T> ok(T data) { return ApiResponse.<T>builder().success(true).data(data).build(); }
}
