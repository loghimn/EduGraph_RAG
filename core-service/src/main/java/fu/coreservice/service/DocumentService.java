package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.document.DocumentDownloadResponse;
import fu.coreservice.dto.document.DocumentProcessingResponse;
import fu.coreservice.dto.document.DocumentResponse;
import fu.coreservice.entity.UploadStatus;
import org.springframework.data.domain.Sort;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {

    DocumentResponse uploadDocument(Long courseId, MultipartFile file);
    PageResponse<DocumentResponse> getDocumentsByCourse(Long courseId, int page, int size, UploadStatus status, Sort.Direction sortDirection);
    DocumentResponse getDocumentById(Long courseId, Long documentId);
    DocumentProcessingResponse getDocumentProcessingStatus(Long courseId, Long documentId);
    DocumentDownloadResponse downloadDocument(Long courseId, Long documentId);

    /**
     * Tạm dừng pipeline AI đang xử lý tài liệu.
     * Chỉ cho phép khi document đang ở trạng thái PROCESSING.
     */
    DocumentResponse pauseProcessing(Long courseId, Long documentId);

    /**
     * Hủy bỏ hoàn toàn tài liệu (xóa file khỏi Supabase + Neo4j graph).
     * Cho phép khi document ở PENDING, PROCESSING, PAUSED hoặc FAILED.
     */
    DocumentResponse cancelDocument(Long courseId, Long documentId);

    /**
     * Retry pipeline AI cho tài liệu bị FAILED hoặc PAUSED.
     * Từ chối nếu lỗi thuộc loại USER_ERROR (lỗi file, định dạng...).
     */
    DocumentResponse retryProcessing(Long courseId, Long documentId);
}
