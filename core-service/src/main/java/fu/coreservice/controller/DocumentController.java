package fu.coreservice.controller;

import fu.coreservice.dto.ApiResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.document.DocumentDownloadResponse;
import fu.coreservice.dto.document.DocumentProcessingResponse;
import fu.coreservice.dto.document.DocumentResponse;
import fu.coreservice.entity.UploadStatus;
import fu.coreservice.service.DocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/document/course")
@SecurityRequirements({
        @SecurityRequirement(name = "bearerAuth"),
        @SecurityRequirement(name = "googleOAuth2")
})
@RequiredArgsConstructor
@Tag(name = "Document Management")
public class DocumentController {

    private final DocumentService documentService;

    @Operation(
            summary = "Upload course document",
            description = "Uploads a document to a course owned by the current instructor."
    )
    @PostMapping(
            value = "/{courseId}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<DocumentResponse>> uploadDocument(
            @PathVariable Long courseId,
            @Parameter(
                    description = "Course material file",
                    required = true
            ) @RequestPart("file") MultipartFile file
    ) {

        DocumentResponse response = documentService.uploadDocument(courseId, file);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<DocumentResponse>builder()
                                .success(true)
                                .message("Document uploaded successfully")
                                .data(response)
                                .build()
        );
    }

    @GetMapping("/{courseId}/documents")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<PageResponse<DocumentResponse>>> getDocuments(
            @PathVariable Long courseId,

            @RequestParam(defaultValue = "0")
            @Min(0)
            int page,

            @RequestParam(defaultValue = "10")
            @Min(1)
            @Max(100)
            int size,

            @RequestParam(required = false)
            UploadStatus status,

            @RequestParam(defaultValue = "DESC")
            Sort.Direction sortDirection
    ) {

        PageResponse<DocumentResponse> response = documentService.getDocumentsByCourse(courseId, page, size, status, sortDirection);

        return ResponseEntity.ok(ApiResponse.<PageResponse<DocumentResponse>>builder()
                        .success(true)
                        .message("Documents retrieved successfully")
                        .data(response)
                        .build()
        );
    }

    @GetMapping("/{courseId}/documents/{documentId}")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<DocumentResponse>> getDocumentById(
            @PathVariable Long courseId,
            @PathVariable Long documentId
    ) {

        DocumentResponse response = documentService.getDocumentById(courseId, documentId);

        return ResponseEntity.ok(ApiResponse.<DocumentResponse>builder()
                        .success(true)
                        .message("Document retrieved successfully")
                        .data(response)
                        .build()
        );
    }

    @GetMapping("/{courseId}/documents/{documentId}/processing")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<DocumentProcessingResponse>> getDocumentProcessingStatus(
            @PathVariable Long courseId,
            @PathVariable Long documentId
    ) {
        DocumentProcessingResponse response = documentService.getDocumentProcessingStatus(courseId, documentId);

        return ResponseEntity.ok(ApiResponse.<DocumentProcessingResponse>builder()
                .success(true)
                .message("Document processing status retrieved successfully")
                .data(response)
                .build());
    }

    @GetMapping("/{courseId}/documents/{documentId}/download")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<DocumentDownloadResponse>> downloadDocument(
            @PathVariable Long courseId,
            @PathVariable Long documentId
    ) {

        DocumentDownloadResponse response = documentService.downloadDocument(courseId, documentId);

        return ResponseEntity.ok(ApiResponse.<DocumentDownloadResponse>builder()
                        .success(true)
                        .message("Document download URL generated successfully")
                        .data(response)
                        .build()
        );
    }

    @Operation(
            summary = "Pause document processing",
            description = "Pauses AI pipeline processing for a document currently in PROCESSING state. "
                    + "The document can be retried later."
    )
    @PatchMapping("/{courseId}/documents/{documentId}/pause")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<DocumentResponse>> pauseProcessing(
            @PathVariable Long courseId,
            @PathVariable Long documentId
    ) {
        DocumentResponse response = documentService.pauseProcessing(courseId, documentId);
        return ResponseEntity.ok(ApiResponse.<DocumentResponse>builder()
                .success(true)
                .message("Document processing paused")
                .data(response)
                .build());
    }

    @Operation(
            summary = "Cancel and delete a document",
            description = "Cancels processing and permanently deletes the document file from storage "
                    + "and its data from the knowledge graph. "
                    + "Allowed when status is PENDING, PROCESSING, PAUSED, or FAILED."
    )
    @DeleteMapping("/{courseId}/documents/{documentId}")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<DocumentResponse>> cancelDocument(
            @PathVariable Long courseId,
            @PathVariable Long documentId
    ) {
        DocumentResponse response = documentService.cancelDocument(courseId, documentId);
        return ResponseEntity.ok(ApiResponse.<DocumentResponse>builder()
                .success(true)
                .message("Document cancelled and removed")
                .data(response)
                .build());
    }

    @Operation(
            summary = "Retry document processing",
            description = "Retries AI pipeline for a document in FAILED or PAUSED state. "
                    + "Rejected if the previous failure was a user-side error (corrupted file, wrong format, etc.)."
    )
    @PostMapping("/{courseId}/documents/{documentId}/retry")
    @PreAuthorize("hasRole('INSTRUCTOR')")
    public ResponseEntity<ApiResponse<DocumentResponse>> retryProcessing(
            @PathVariable Long courseId,
            @PathVariable Long documentId
    ) {
        DocumentResponse response = documentService.retryProcessing(courseId, documentId);
        return ResponseEntity.ok(ApiResponse.<DocumentResponse>builder()
                .success(true)
                .message("Document processing retry triggered")
                .data(response)
                .build());
    }

}
