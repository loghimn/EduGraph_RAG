package fu.coreservice.service.impl;

import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.document.DocumentPublishRequest;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.DocumentRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.repository.DocumentProcessingJobRepository;
import fu.coreservice.security.CourseAccessPolicy;
import fu.coreservice.service.DocumentReviewService;
import fu.coreservice.utils.SecurityUtils;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentReviewServiceImpl implements DocumentReviewService {
    private final DocumentRepository documents;
    private final UserRepository users;
    private final AiCoreClient ai;
    private final DocumentProcessingJobRepository jobs;

    private User actor() {
        return users.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private void authorize(Document document, Long courseId, User user) {
        Course course = document.getCourse();
        if (!course.getCourseId().equals(courseId) || course.isNotebook()
                || user.getRole() != UserRole.INSTRUCTOR) {
            throw new AppException(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        CourseAccessPolicy.requireOwner(course, user);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> preview(Long courseId, Long documentId) {
        User user = actor();
        Document document = documents.findById(documentId)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        authorize(document, courseId, user);
        return ai.previewDocument(documentId, courseId, user.getUserId());
    }

    @Override
    @Transactional
    public void publish(Long courseId, Long documentId, DocumentPublishRequest request) {
        User user = actor();
        // Same row lock as cancel/retry/poll. Keep the draft retryable if the
        // upstream response is lost; Python stores a transactional publication receipt.
        Document document = documents.findForUpdate(documentId)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        authorize(document, courseId, user);
        if (document.getDocStatus() != UploadStatus.AWAITING_REVIEW
                && document.getDocStatus() != UploadStatus.PROCESSING
                && document.getDocStatus() != UploadStatus.COMPLETED) {
            throw new AppException(ErrorCode.DOCUMENT_REVIEW_NOT_READY);
        }
        ai.publishDocument(documentId, courseId, user.getUserId(), request);
        document.setDocStatus(UploadStatus.COMPLETED);
        document.setParseAt(LocalDateTime.now());
        documents.save(document);
        jobs.findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(documentId).ifPresent(job -> {
            job.setJobStatus(JobStatus.DONE);
            jobs.save(job);
        });
    }
}
