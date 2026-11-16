package fu.coreservice.service;

import fu.coreservice.dto.document.DocumentPublishRequest;
import fu.coreservice.dto.document.DocumentDraftUpdateRequest;
import java.util.Map;

public interface DocumentReviewService {
    Map<String, Object> preview(Long courseId, Long documentId);
    Map<String, Object> updateDraft(Long courseId, Long documentId, DocumentDraftUpdateRequest request);
    void publish(Long courseId, Long documentId, DocumentPublishRequest request);
}
