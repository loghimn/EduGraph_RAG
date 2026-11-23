package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.chat.ChatCourseOption;
import fu.coreservice.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/chat/history-courses")
public class ChatCourseHistoryController {
    private final ChatHistoryService history;

    @GetMapping
    public ApiResponse<List<ChatCourseOption>> courses() {
        return ApiResponse.<List<ChatCourseOption>>builder().success(true).data(history.courses()).build();
    }
}
