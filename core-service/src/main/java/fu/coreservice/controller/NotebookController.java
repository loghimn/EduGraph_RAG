package fu.coreservice.controller;
import fu.coreservice.dto.*;
import fu.coreservice.dto.notebook.*;
import fu.coreservice.service.NotebookService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

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
