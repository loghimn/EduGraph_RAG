package fu.coreservice.service.impl;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.document.DocumentDownloadResponse;
import fu.coreservice.dto.document.DocumentProcessingResponse;
import fu.coreservice.dto.document.DocumentResponse;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.DocumentProcessingJobRepository;
import fu.coreservice.repository.DocumentRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.DocumentService;
import fu.coreservice.service.AiDocumentProcessingService;
import fu.coreservice.service.SupabaseStorageService;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentProcessingJobRepository documentProcessingJobRepository;
    private final CourseRepository courseRepository;
    private final SupabaseStorageService supabaseStorageService;
    private final UserRepository userRepository;
    private final AiDocumentProcessingService aiDocumentProcessingService;
    private final AiCoreClient aiCoreClient;

    @Override
    @Transactional
    public DocumentResponse uploadDocument(Long courseId, MultipartFile file) {

        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));

        if (!course.getUser().getUserId().equals(currentUser.getUserId())) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }

        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.FILE_EMPTY);
        }

        Document document = Document.builder()
                .course(course)
                .user(currentUser)
                .fileName(file.getOriginalFilename())
                .fileType(file.getContentType())
                .fileSizeKb(file.getSize() / 1024)
                .docStatus(UploadStatus.PENDING)
                .build();
        document = documentRepository.save(document);

        String storagePath;
        try {
            storagePath = supabaseStorageService.uploadCourseMaterial(file, courseId, document.getDocumentId());
        } catch (AppException e) {
            documentRepository.delete(document);
            throw e;
        } catch (Exception e) {
            log.error("Failed to upload document to Supabase. documentId={}", document.getDocumentId(), e);
            documentRepository.delete(document);
            throw new AppException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        document.setFileUrlPath(storagePath);
        document = documentRepository.save(document);

        DocumentProcessingJob processingJob = DocumentProcessingJob.builder()
                        .document(document)
                        .jobType(JobType.NONE)
                        .jobStatus(JobStatus.QUEUED)
                        .build();
        documentProcessingJobRepository.save(processingJob);

        // Sau khi commit, đẩy file + lệnh /ingest sang AI core (Python) bất đồng bộ.
        // Trả response ngay với docStatus=PENDING; poller sẽ đồng bộ COMPLETED/FAILED.
        triggerAiPipelineAfterCommit(courseId, document.getDocumentId(), currentUser.getUserId(), file);

        return mapToResponse(document);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> getDocumentsByCourse(Long courseId, int page, int size, UploadStatus status, Sort.Direction sortDirection) {

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));

        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, "uploadAt"));

        Page<Document> documentPage;

        if (status != null) {
            documentPage = documentRepository.findByCourseCourseIdAndDocStatus(course.getCourseId(), status, pageable);
        } else {
            documentPage = documentRepository.findByCourseCourseId(course.getCourseId(), pageable);
        }

        List<DocumentResponse> content = documentPage.getContent()
                .stream()
                .map(this::mapToResponse)
                .toList();

        return PageResponse.<DocumentResponse>builder()
                .content(content)
                .page(documentPage.getNumber())
                .size(documentPage.getSize())
                .totalElements(documentPage.getTotalElements())
                .totalPages(documentPage.getTotalPages())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocumentById(Long courseId, Long documentId) {

        courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));

        Document document = documentRepository.findByDocumentIdAndCourseCourseId(documentId,courseId)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));

        return mapToResponse(document);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentProcessingResponse getDocumentProcessingStatus(Long courseId, Long documentId) {
        getCourseOwnedByCurrentInstructor(courseId);

        Document document = documentRepository.findByDocumentIdAndCourseCourseId(documentId, courseId)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));

        DocumentProcessingJob job = documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(document.getDocumentId())
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));

        return DocumentProcessingResponse.builder()
                .documentId(document.getDocumentId())
                .jobStatus(job.getJobStatus())
                .jobType(job.getJobType())
                .errorMessage(job.getErrorMessage())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDownloadResponse downloadDocument(Long courseId, Long documentId) {

        getCourseOwnedByCurrentInstructor(courseId);

        Document document = documentRepository.findByDocumentIdAndCourseCourseId(documentId, courseId)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));

        String signedUrl = supabaseStorageService.createSignedUrl(document.getFileUrlPath(), 3600);

        return DocumentDownloadResponse.builder()
                .documentId(document.getDocumentId())
                .fileName(document.getFileName())
                .downloadUrl(signedUrl)
                .build();
    }

    private DocumentResponse mapToResponse(Document document) {

        return DocumentResponse.builder()
                .documentId(document.getDocumentId())
                .courseId(document.getCourse().getCourseId())
                .uploadedBy(document.getUser().getUserId())
                .fileName(document.getFileName())
                .fileType(document.getFileType())
                .fileSizeKb(document.getFileSizeKb())
                .docStatus(document.getDocStatus())
                .uploadAt(document.getUploadAt())
                .parseAt(document.getParseAt())
                .build();
    }

    private User getCurrentUser() {
        String email = SecurityUtils.getCurrentUserEmail();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private void getCourseOwnedByCurrentInstructor(Long courseId) {

        User currentUser = getCurrentUser();

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));

        if (!course.getUser().getUserId().equals(currentUser.getUserId())) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }

    }

    /**
     * Sau khi transaction commit, đẩy file + lệnh /ingest sang AI core (Python) bất đồng bộ.
     * File được đọc vào bộ nhớ TRƯỚC commit vì MultipartFile không còn hợp lệ sau khi request kết thúc.
     */
    private void triggerAiPipelineAfterCommit(Long courseId, Long documentId, Long ownerId, MultipartFile file) {

        String filename = file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank()
                ? file.getOriginalFilename()
                : "document";

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("Failed to read uploaded file for AI pipeline. documentId={}", documentId, e);
            throw new AppException(ErrorCode.FILE_READ_FAILED);
        }

        MediaType mediaType = parseMediaType(file.getContentType());
        Resource content = new ByteArrayResource(bytes);

        Runnable trigger = () -> aiDocumentProcessingService.triggerPipelineAsync(
                courseId, documentId, ownerId, filename, mediaType, content);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    trigger.run();
                }
            });
        } else {
            trigger.run();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Pause
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tạm dừng pipeline AI đang xử lý tài liệu.
     * Chỉ cho phép khi document đang ở trạng thái PROCESSING.
     * Chờ Python dừng tác vụ trước khi lưu PAUSED.
     */
    @Override
    @Transactional
    public DocumentResponse pauseProcessing(Long courseId, Long documentId) {
        getCourseOwnedByCurrentInstructor(courseId);

        Document document = getDocumentForUpdate(courseId, documentId);

        if (document.getDocStatus() != UploadStatus.PROCESSING) {
            throw new AppException(ErrorCode.PAUSE_NOT_ALLOWED);
        }

        aiCoreClient.pauseDocument(documentId, courseId, getCurrentUser().getUserId());
        document.setDocStatus(UploadStatus.PAUSED);
        documentRepository.save(document);

        documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(documentId)
                .ifPresent(job -> {
                    job.setJobStatus(JobStatus.PAUSED);
                    documentProcessingJobRepository.save(job);
                });

        log.info("Document processing paused. documentId={}", documentId);
        return mapToResponse(document);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cancel
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hủy bỏ hoàn toàn tài liệu:
     * Dừng Python và dọn graph trước khi xóa file Storage.
     * Giữ Document/job ở CANCELLED để lưu lịch sử.
     *
     * Cho phép khi status là PENDING, PROCESSING, PAUSED hoặc FAILED.
     */
    @Override
    @Transactional
    public DocumentResponse cancelDocument(Long courseId, Long documentId) {
        User currentUser = getCurrentUser();
        getCourseOwnedByCurrentInstructor(courseId);

        Document document = getDocumentForUpdate(courseId, documentId);

        UploadStatus currentStatus = document.getDocStatus();
        if (currentStatus == UploadStatus.COMPLETED) {
            throw new AppException(ErrorCode.CANCEL_NOT_ALLOWED);
        }
        if (currentStatus == UploadStatus.CANCELLED) {
            throw new AppException(ErrorCode.DOCUMENT_CANCELLED);
        }

        // The row lock serializes cancellation against trigger/retry/poll writes.
        aiCoreClient.cancelDocument(documentId, courseId, currentUser.getUserId());
        document.setDocStatus(UploadStatus.CANCELLED);
        documentRepository.save(document);

        documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(documentId)
                .ifPresent(job -> {
                    job.setJobStatus(JobStatus.CANCELLED);
                    documentProcessingJobRepository.save(job);
                });

        // Dọn dẹp Supabase (best-effort, không throw nếu file đã bị xóa)
        if (document.getFileUrlPath() != null) {
            try {
                supabaseStorageService.deleteFile(document.getFileUrlPath());
            } catch (Exception e) {
                log.warn("Failed to delete Supabase file during cancel. documentId={}, path={}, reason={}",
                        documentId, document.getFileUrlPath(), e.getMessage());
            }
        }

        log.info("Document cancelled. documentId={}", documentId);
        return mapToResponse(document);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Retry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Thử lại pipeline AI cho tài liệu bị FAILED hoặc PAUSED.
     * Từ chối nếu lỗi trước đó là USER_ERROR (không thể sửa bằng retry).
     * File được tải lại từ Supabase Storage để truyền sang Python.
     */
    @Override
    @Transactional
    public DocumentResponse retryProcessing(Long courseId, Long documentId) {
        User currentUser = getCurrentUser();
        getCourseOwnedByCurrentInstructor(courseId);

        Document document = getDocumentForUpdate(courseId, documentId);

        UploadStatus status = document.getDocStatus();
        if (status != UploadStatus.FAILED && status != UploadStatus.PAUSED) {
            throw new AppException(ErrorCode.RETRY_NOT_ALLOWED);
        }

        // Kiểm tra loại lỗi — USER_ERROR không được retry
        documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(documentId)
                .ifPresent(job -> {
                    if (job.getErrorCategory() == ErrorCategory.USER_ERROR) {
                        throw new AppException(ErrorCode.USER_ERROR_NO_RETRY);
                    }
                });

        String filename = document.getFileName() != null ? document.getFileName() : "document";
        MediaType mediaType = parseMediaType(document.getFileType());

        // Tải lại nội dung file từ Supabase để truyền cho Python
        byte[] fileBytes = supabaseStorageService.downloadFile(document.getFileUrlPath());
        Resource content = new ByteArrayResource(fileBytes) {
            @Override public String getFilename() { return filename; }
        };

        // triggerPipelineRetry tạo job mới + reset status về PENDING trước khi chạy
        aiDocumentProcessingService.triggerPipelineRetry(
                courseId, documentId, currentUser.getUserId(), filename, mediaType, content);

        // Reload để trả status mới nhất
        document = documentRepository.findById(documentId).orElseThrow();
        log.info("Document retry triggered. documentId={}", documentId);
        return mapToResponse(document);
    }

    private Document getDocumentForUpdate(Long courseId, Long documentId) {
        Document document = documentRepository.findForUpdate(documentId)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (!document.getCourse().getCourseId().equals(courseId)) {
            throw new AppException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return document;
    }

    private MediaType parseMediaType(String contentType) {
        try {
            return contentType == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
