package fu.coreservice.service;

import org.springframework.web.multipart.MultipartFile;

public interface SupabaseStorageService {

    String uploadCourseMaterial(MultipartFile file, Long courseId, Long documentId);
    String createSignedUrl(String filePath, int expiresInSeconds);

    /**
     * Xóa file khỏi Supabase Storage theo đường dẫn.
     * Dùng khi cancel document.
     */
    void deleteFile(String filePath);

    /**
     * Tải nội dung file từ Supabase Storage.
     * Dùng khi retry document — cần truyền lại file cho Python AI core.
     */
    byte[] downloadFile(String filePath);
}
