package fu.coreservice.service.impl;

import fu.coreservice.service.AiDocumentProcessingService;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiDocumentStatusResponse;
import fu.coreservice.dto.ai.AiIngestRequest;
import fu.coreservice.entity.Document;
import fu.coreservice.entity.DocumentProcessingJob;
import fu.coreservice.entity.ErrorCategory;
import fu.coreservice.entity.JobStatus;
import fu.coreservice.entity.JobType;
import fu.coreservice.entity.UploadStatus;
import fu.coreservice.exception.AiCoreException;
import fu.coreservice.exception.AppException;
import fu.coreservice.repository.DocumentProcessingJobRepository;
import fu.coreservice.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Điều phối pipeline xử lý tài liệu trên AI core (Python):
 * - triggerPipelineAsync: gửi file + gọi /ingest sau khi transaction upload đã commit
 *   (chạy trên virtual thread để không block request);
 * - syncProcessingDocuments: @Scheduled poll GET /documents/{id}/status để đồng bộ
 *   Document.status (Neo4j/Python) sang UploadStatus + DocumentProcessingJob (Postgres/Java).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AiDocumentProcessingServiceImpl implements AiDocumentProcessingService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final AiCoreClient aiCoreClient;
    private final DocumentRepository documentRepository;
    private final DocumentProcessingJobRepository documentProcessingJobRepository;
    private final TransactionTemplate transactionTemplate;

    private final ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final int MAX_PIPELINE_CONCURRENCY = 4;
    private final Semaphore pipelineConcurrency = new Semaphore(MAX_PIPELINE_CONCURRENCY);

    private static final int MAX_RETRY_ATTEMPTS = 3;
    // Lỗi Python 4xx do người dùng — KHÔNG retry (permanent)
    private static final Set<Integer> USER_ERROR_HTTP_STATUSES = Set.of(400, 413, 415, 422);
    // Từ khóa trong error message chỉ USER_ERROR
    private static final Set<String> USER_ERROR_KEYWORDS = Set.of(
            "unsupported file", "file is empty", "scanned pdf", "password", "encrypted",
            "file exceeds", "invalid file", "file name must", "path traversal"
    );

    @Override
    public void triggerPipelineAsync(Long courseId, Long documentId, Long ownerId, String filename,
                                     MediaType mediaType, Resource content) {
        Long jobId = findLatestJob(documentId).map(DocumentProcessingJob::getDocumentProcessingJobId).orElse(null);
        pipelineExecutor.submit(() -> runPipeline(courseId, documentId, ownerId, filename, mediaType, content, 0, false, jobId));
    }

    private void runPipeline(Long courseId, Long documentId, Long ownerId, String filename,
                             MediaType mediaType, Resource content, int attemptNumber, boolean restart, Long jobId) {
        try {
            // Retry backoff: 0s, 5s, 15s trước khi thử lại
            if (attemptNumber > 0) {
                long backoffMs = attemptNumber == 1 ? 5_000L : 15_000L;
                log.info("Retrying AI pipeline. documentId={}, attempt={}, backoffMs={}", documentId, attemptNumber, backoffMs);
                Thread.sleep(backoffMs);
            }
            // Limit concurrent workers inside the locked (transaction + HTTP upload)
            // section so they cannot exhaust the JDBC connection pool.
            pipelineConcurrency.acquire();
            try {
                // Hold the same row lock as pause/cancel through the upstream calls.
                // A queued/backoff worker must not restart a cancelled document.
                transactionTemplate.executeWithoutResult(tx -> {
                Document document = documentRepository.findForUpdate(documentId).orElse(null);
                if (document == null || document.getDocStatus() != UploadStatus.PENDING || !isCurrentJob(documentId, jobId)) {
                    return;
                }
                try {
                    aiCoreClient.storeDocumentFile(documentId, filename, mediaType, content);
                } catch (AiCoreException e) {
                    // An earlier /ingest ACK may have timed out. Python rejects
                    // overwriting a running file; /ingest safely acknowledges it.
                    if (e.getHttpStatus() != 409) {
                        throw e;
                    }
                }
                aiCoreClient.triggerIngest(AiIngestRequest.builder()
                    .documentId(String.valueOf(documentId))
                    .courseId(String.valueOf(courseId))
                    .userId(String.valueOf(ownerId))
                    .filename(filename)
                    .restart(restart)
                    .runId(String.valueOf(jobId))
                    .build());
                document.setDocStatus(UploadStatus.PROCESSING);
                documentRepository.save(document);
                findLatestJob(documentId).ifPresent(job -> {
                    job.setJobStatus(JobStatus.RUNNING);
                    documentProcessingJobRepository.save(job);
                });
            });
            } finally {
                pipelineConcurrency.release();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markFailed(documentId, "Pipeline interrupted during retry backoff", ErrorCategory.SYSTEM_ERROR, jobId);
            return;
        } catch (RuntimeException e) {
            ErrorCategory category = classifyError(e);
            int retryCount = getRetryCount(documentId);

            if (category == ErrorCategory.SYSTEM_ERROR && retryCount < MAX_RETRY_ATTEMPTS) {
                // SYSTEM_ERROR + còn lượt retry → thử lại
                log.warn("AI pipeline failed (attempt {}/{}), will retry. documentId={}, reason={}",
                        attemptNumber + 1, MAX_RETRY_ATTEMPTS + 1, documentId, e.getMessage());
                incrementRetryCount(documentId, jobId);
                runPipeline(courseId, documentId, ownerId, filename, mediaType, content, attemptNumber + 1, restart, jobId);
            } else {
                // USER_ERROR hoặc đã hết retry → FAILED vĩnh viễn
                log.error("AI pipeline trigger failed permanently. documentId={}, category={}, attempt={}, reason={}",
                        documentId, category, attemptNumber + 1, e.getMessage());
                markFailed(documentId, e.getMessage(), category, jobId);
            }
            return;
        }

    }

    private void markFailed(Long documentId, String message, ErrorCategory category, Long jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            Document document = documentRepository.findForUpdate(documentId).orElse(null);
            if (document == null || document.getDocStatus() != UploadStatus.PENDING || !isCurrentJob(documentId, jobId)) {
                return;
            }
            document.setDocStatus(UploadStatus.FAILED);
            documentRepository.save(document);
            findLatestJob(documentId).ifPresent(job -> {
                job.setJobStatus(JobStatus.FAILED);
                job.setErrorMessage(truncate(message));
                job.setErrorCategory(category);
                documentProcessingJobRepository.save(job);
            });
        });
    }

    /** Phân loại lỗi: USER_ERROR (không retry) hay SYSTEM_ERROR (có thể retry). */
    private ErrorCategory classifyError(RuntimeException e) {
        // AiCoreException mang HTTP status gốc từ Python
        if (e instanceof fu.coreservice.exception.AiCoreException ace) {
            int status = ace.getHttpStatus();
            if (USER_ERROR_HTTP_STATUSES.contains(status)) {
                return ErrorCategory.USER_ERROR;
            }
            // Chỉ áp dụng keyword matching cho lỗi Python 4xx; lỗi 5xx/infra
            // không được phân loại USER_ERROR dù message chứa từ khóa.
            if (status < 500) {
                String msg = ace.getDetail() == null ? "" : ace.getDetail().toLowerCase();
                for (String keyword : USER_ERROR_KEYWORDS) {
                    if (msg.contains(keyword)) {
                        return ErrorCategory.USER_ERROR;
                    }
                }
            }
            return ErrorCategory.SYSTEM_ERROR;
        }
        // Lỗi không phải từ Python (network/infra) → luôn retryable
        return ErrorCategory.SYSTEM_ERROR;
    }

    private int getRetryCount(Long documentId) {
        return findLatestJob(documentId)
                .map(DocumentProcessingJob::getRetryCount)
                .orElse(0);
    }

    private void incrementRetryCount(Long documentId, Long jobId) {
        transactionTemplate.executeWithoutResult(status -> {
            Document document = documentRepository.findForUpdate(documentId).orElse(null);
            if (document == null || document.getDocStatus() != UploadStatus.PENDING || !isCurrentJob(documentId, jobId)) {
                return;
            }
            findLatestJob(documentId).ifPresent(job -> {
                job.setRetryCount(job.getRetryCount() + 1);
                documentProcessingJobRepository.save(job);
            });
        });
    }

    /**
     * Trigger retry thủ công (từ API /retry): tạo job mới, chạy lại pipeline.
     */
    @Override
    public void triggerPipelineRetry(Long courseId, Long documentId, Long ownerId, String filename,
                                     MediaType mediaType, Resource content) {
        // Tạo job mới để track lần retry này
        Long jobId = transactionTemplate.execute(status -> {
            Document document = documentRepository.findForUpdate(documentId).orElseThrow();
            document.setDocStatus(UploadStatus.PENDING);
            documentRepository.save(document);
            DocumentProcessingJob newJob = DocumentProcessingJob.builder()
                    .document(document)
                    .jobType(JobType.NONE)
                    .jobStatus(JobStatus.QUEUED)
                    .retryCount(0)
                    .build();
            return documentProcessingJobRepository.save(newJob).getDocumentProcessingJobId();
        });
        // The outer retry transaction owns the row lock. Launch only after commit.
        Runnable trigger = () -> pipelineExecutor.submit(() -> runPipeline(
                courseId, documentId, ownerId, filename, mediaType, content, 0, true, jobId));
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

    private boolean isCurrentJob(Long documentId, Long jobId) {
        return jobId != null && findLatestJob(documentId)
                .map(job -> jobId.equals(job.getDocumentProcessingJobId())).orElse(false);
    }

    private Optional<DocumentProcessingJob> findLatestJob(Long documentId) {
        return documentProcessingJobRepository
                .findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(documentId);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        return message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message;
    }

    /**
     * Đồng bộ định kỳ Document.status từ Neo4j (Python) sang Postgres.
     * Mapping: DONE -> COMPLETED, FAILED -> FAILED, PROCESSING/UPLOADED -> giữ nguyên.
     * Bỏ qua document CANCELLED hoặc PAUSED (không cho AI core override trạng thái đã set).
     */
    @Scheduled(fixedDelayString = "${ai-core.status-poll-interval-ms:15000}")
    @Override
    public void syncProcessingDocuments() {
        List<Document> processingDocuments = documentRepository.findByDocStatus(UploadStatus.PROCESSING);
        for (Document document : processingDocuments) {
            Long documentId = document.getDocumentId();
            try {
                transactionTemplate.executeWithoutResult(tx -> {
                    Document current = documentRepository.findForUpdate(documentId).orElse(null);
                    if (current == null || current.getDocStatus() != UploadStatus.PROCESSING) {
                        return;
                    }
                    AiDocumentStatusResponse status = aiCoreClient.getDocumentStatus(
                            documentId, current.getCourse().getCourseId());
                    applyStatus(current, status);
                });
            } catch (AppException e) {
                // AI core không phản hồi — giữ nguyên trạng thái, thử lại ở lần poll sau
                log.warn("Skipping status sync, AI core unavailable. documentId={}", documentId);
            } catch (Exception e) {
                log.warn("Failed to sync document status. documentId={}, reason={}", documentId, e.getMessage());
            }
        }
    }


    private void applyStatus(Document document, AiDocumentStatusResponse status) {
        String pythonStatus = status == null || status.getStatus() == null ? "" : status.getStatus();
        JobType currentStep = parseCurrentStep(status, document.getDocumentId());
        Optional<DocumentProcessingJob> latestJob = findLatestJob(document.getDocumentId());
        switch (pythonStatus) {
            case "PAUSED" -> {
                document.setDocStatus(UploadStatus.PAUSED);
                documentRepository.save(document);
                latestJob.ifPresent(job -> {
                    job.setJobStatus(JobStatus.PAUSED);
                    documentProcessingJobRepository.save(job);
                });
            }
            // A cancel request may have timed out after Python stopped. Leave
            // Java retryable until DELETE is repeated and Storage is cleaned up.
            case "CANCELLED" -> log.warn("Python cancelled document {}; repeat DELETE to finish Storage cleanup", document.getDocumentId());
            case "DONE" -> {
                document.setDocStatus(UploadStatus.COMPLETED);
                documentRepository.save(document);
                latestJob.ifPresent(job -> {
                    job.setJobStatus(JobStatus.DONE);
                    if (currentStep != null) {
                        job.setJobType(currentStep);
                    }
                    documentProcessingJobRepository.save(job);
                });
                log.info("Document processing completed. documentId={}", document.getDocumentId());
            }
            case "FAILED" -> {
                document.setDocStatus(UploadStatus.FAILED);
                documentRepository.save(document);
                latestJob.ifPresent(job -> {
                    job.setJobStatus(JobStatus.FAILED);
                    if (currentStep != null) {
                        job.setJobType(currentStep);
                    }
                    job.setErrorMessage(truncate(status.getError()));
                    documentProcessingJobRepository.save(job);
                });
                log.warn("Document processing failed. documentId={}", document.getDocumentId());
            }
            case "PROCESSING", "UPLOADED" -> {
                latestJob.ifPresent(job -> {
                    if (currentStep != null && job.getJobType() != currentStep) {
                        job.setJobType(currentStep);
                        documentProcessingJobRepository.save(job);
                    }
                });
            }
            default -> log.warn("Unknown AI status '{}'. documentId={}", pythonStatus, document.getDocumentId());
        }
    }

    private JobType parseCurrentStep(AiDocumentStatusResponse status, Long documentId) {
        if (status == null || status.getCurrentStep() == null || status.getCurrentStep().isBlank()) {
            return null;
        }
        try {
            return JobType.valueOf(status.getCurrentStep());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown AI processing step '{}'. documentId={}", status.getCurrentStep(), documentId);
            return null;
        }
    }
}
