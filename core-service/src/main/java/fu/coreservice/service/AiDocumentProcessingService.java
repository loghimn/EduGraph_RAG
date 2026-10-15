package fu.coreservice.service;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.ai.AiDocumentStatusResponse;
import fu.coreservice.dto.ai.AiIngestRequest;
import fu.coreservice.entity.Document;
import fu.coreservice.entity.DocumentProcessingJob;
import fu.coreservice.entity.JobStatus;
import fu.coreservice.entity.JobType;
import fu.coreservice.entity.UploadStatus;
import fu.coreservice.exception.AppException;
import fu.coreservice.repository.DocumentProcessingJobRepository;
import fu.coreservice.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
public class AiDocumentProcessingService {

    private static final int MAX_ERROR_LENGTH = 1000;

    private final AiCoreClient aiCoreClient;
    private final DocumentRepository documentRepository;
    private final DocumentProcessingJobRepository documentProcessingJobRepository;
    private final TransactionTemplate transactionTemplate;

    private final ExecutorService pipelineExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void triggerPipelineAsync(Long courseId, Long documentId, Long ownerId, String filename,
                                     MediaType mediaType, Resource content) {
        pipelineExecutor.submit(() -> runPipeline(courseId, documentId, ownerId, filename, mediaType, content));
    }

    private void runPipeline(Long courseId, Long documentId, Long ownerId, String filename,
                             MediaType mediaType, Resource content) {
        try {
            aiCoreClient.storeDocumentFile(documentId, filename, mediaType, content);
            aiCoreClient.triggerIngest(AiIngestRequest.builder()
                    .documentId(String.valueOf(documentId))
                    .courseId(String.valueOf(courseId))
                    .userId(String.valueOf(ownerId))
                    .filename(filename)
                    .build());
        } catch (RuntimeException e) {
            log.error("AI pipeline trigger failed. documentId={}, reason={}", documentId, e.getMessage());
            markFailed(documentId, e.getMessage());
            return;
        }

        markProcessing(documentId);
        log.info("AI pipeline processing. documentId={}", documentId);
    }

    private void markProcessing(Long documentId) {
        transactionTemplate.executeWithoutResult(status -> {
            documentRepository.findById(documentId).ifPresent(document -> {
                document.setDocStatus(UploadStatus.PROCESSING);
                documentRepository.save(document);
            });
            findLatestJob(documentId).ifPresent(job -> {
                job.setJobStatus(JobStatus.RUNNING);
                documentProcessingJobRepository.save(job);
            });
        });
    }

    private void markFailed(Long documentId, String message) {
        transactionTemplate.executeWithoutResult(status -> {
            documentRepository.findById(documentId).ifPresent(document -> {
                document.setDocStatus(UploadStatus.FAILED);
                documentRepository.save(document);
            });
            findLatestJob(documentId).ifPresent(job -> {
                job.setJobStatus(JobStatus.FAILED);
                job.setErrorMessage(truncate(message));
                documentProcessingJobRepository.save(job);
            });
        });
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
     */
    @Transactional
    @Scheduled(fixedDelayString = "${ai-core.status-poll-interval-ms:15000}")
    public void syncProcessingDocuments() {
        List<Document> processingDocuments = documentRepository.findByDocStatus(UploadStatus.PROCESSING);
        for (Document document : processingDocuments) {
            Long documentId = document.getDocumentId();
            Long courseId = document.getCourse().getCourseId();
            try {
                AiDocumentStatusResponse status = aiCoreClient.getDocumentStatus(documentId, courseId);
                applyStatus(document, status);
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
