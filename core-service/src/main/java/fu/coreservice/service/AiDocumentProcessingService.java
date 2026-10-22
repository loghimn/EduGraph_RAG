package fu.coreservice.service;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

public interface AiDocumentProcessingService {

    void triggerPipelineAsync(Long courseId, Long documentId, Long ownerId, String filename, MediaType mediaType, Resource content);

    void syncProcessingDocuments();
}
