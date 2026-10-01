package fu.coreservice.service;

import org.springframework.web.multipart.MultipartFile;

public interface SupabaseStorageService {

    String uploadCourseMaterial(MultipartFile file, Long courseId, Long documentId);
    String createSignedUrl(String filePath, int expiresInSeconds);

}
