package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.entity.MasteryStatus;
import fu.coreservice.service.LearnerMasteryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/courses/{courseId}/masteries/me")
public class LearnerMasteryController {
    private final LearnerMasteryService service;
    public record UpdateRequest(@NotNull MasteryStatus status) {}

    @GetMapping
    public ApiResponse<List<LearnerMasteryService.MasteryView>> list(@PathVariable Long courseId) {
        return ApiResponse.<List<LearnerMasteryService.MasteryView>>builder().success(true)
                .data(service.list(courseId)).build();
    }

    @PatchMapping("/concepts/{conceptId}")
    public ApiResponse<LearnerMasteryService.MasteryView> update(@PathVariable Long courseId,
            @PathVariable String conceptId, @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.<LearnerMasteryService.MasteryView>builder().success(true)
                .data(service.update(courseId, conceptId, request.status())).build();
    }

    @GetMapping("/concepts/{conceptId}/history")
    public ApiResponse<List<LearnerMasteryService.HistoryView>> history(@PathVariable Long courseId,
            @PathVariable String conceptId) {
        return ApiResponse.<List<LearnerMasteryService.HistoryView>>builder().success(true)
                .data(service.history(courseId, conceptId)).build();
    }
}
