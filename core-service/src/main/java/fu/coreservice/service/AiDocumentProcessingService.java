package fu.coreservice.service;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public interface AiDocumentProcessingService {

    void triggerPipelineAsync(Long courseId, Long documentId, Long ownerId, String filename, MediaType mediaType, Resource content);

    void syncProcessingDocuments();

    /**
     * Trigger thủ công lại pipeline (dùng cho API /retry).
     * Tạo job mới, reset status PENDING, chạy lại từ đầu với exponential backoff tự động.
     */
    void triggerPipelineRetry(Long courseId, Long documentId, Long ownerId, String filename, MediaType mediaType, Resource content);
}
