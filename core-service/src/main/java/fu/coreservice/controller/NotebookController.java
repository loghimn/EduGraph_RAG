package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.notebook.NotebookRequest;
import fu.coreservice.dto.notebook.NotebookResponse;
import fu.coreservice.service.NotebookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notebooks")
@RequiredArgsConstructor
@Validated
public class NotebookController {
    private final NotebookService notebookService;
    @GetMapping
    public ApiResponse<PageResponse<NotebookResponse>> list(
        @RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="12") @Min(1) @Max(100) int size) {
        return ApiResponse.<PageResponse<NotebookResponse>>builder().success(true).data(notebookService.list(page,size)).build();
    }
    @GetMapping("/{id}")
    public ApiResponse<NotebookResponse> get(@PathVariable Long id) {
        return ApiResponse.<NotebookResponse>builder().success(true).data(notebookService.get(id)).build();
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NotebookResponse> create(@Valid @RequestBody NotebookRequest request) {
        return ApiResponse.<NotebookResponse>builder().success(true).data(notebookService.create(request)).build();
    }
}
