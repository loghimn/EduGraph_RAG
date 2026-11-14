package fu.coreservice.service;

import fu.coreservice.dto.document.DocumentPublishRequest;
import java.util.Map;

public interface DocumentReviewService {
    Map<String, Object> preview(Long courseId, Long documentId);
    void publish(Long courseId, Long documentId, DocumentPublishRequest request);
}
