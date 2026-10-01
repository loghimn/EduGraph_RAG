package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.document.DocumentDownloadResponse;
import fu.coreservice.dto.document.DocumentResponse;
import fu.coreservice.entity.UploadStatus;
import org.springframework.data.domain.Sort;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    DocumentResponse uploadDocument(Long courseId, MultipartFile file);
    PageResponse<DocumentResponse> getDocumentsByCourse(Long courseId, int page, int size, UploadStatus status, Sort.Direction sortDirection);
    DocumentResponse getDocumentById(Long courseId, Long documentId);
    DocumentDownloadResponse downloadDocument(Long courseId, Long documentId);
}
