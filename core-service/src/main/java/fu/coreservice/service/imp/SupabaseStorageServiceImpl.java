package fu.coreservice.service.imp;

import fu.coreservice.config.SupabaseStorageConfig;
import fu.coreservice.service.SupabaseStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SupabaseStorageServiceImpl implements SupabaseStorageService {

    private final SupabaseStorageConfig supabaseStorageConfig;
    private final RestTemplate supabaseRestTemplate;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "text/plain"
    );

    private static final long MAX_FILE_SIZE = 40 * 1024 * 1024; // 40MB

    @Override
    public String uploadCourseMaterial(MultipartFile file, Long courseId, Long documentId) {
        validateFile(file);

        String originalFilename = file.getOriginalFilename();
        String extension = getFileExtension(originalFilename);

        String safeFilename = UUID.randomUUID() + extension;

        String filePath = String.format(
                "course/%d/document/%d/%s",
                courseId,
                documentId,
                safeFilename
        );

        try {
            String uploadUrl = supabaseStorageConfig.getUploadUrl(filePath);

            HttpHeaders headers = new HttpHeaders();

            headers.set("apikey", supabaseStorageConfig.getSecretKey());

            headers.set("Authorization", "Bearer " + supabaseStorageConfig.getSecretKey());

            headers.setContentType(MediaType.parseMediaType(file.getContentType()));

            HttpEntity<ByteArrayResource> requestEntity = new HttpEntity<>(
                            new ByteArrayResource(file.getBytes()) {
                                @Override
                                public String getFilename() {
                                    return safeFilename;
                                }}, headers);

            ResponseEntity<String> response = supabaseRestTemplate.exchange(
                            uploadUrl,
                            HttpMethod.POST,
                            requestEntity,
                            String.class
                    );

            if (!response.getStatusCode().is2xxSuccessful()) {
                log.error("Supabase upload failed. Status: {}, Body: {}", response.getStatusCode(), response.getBody());

                throw new RuntimeException("Failed to upload file to Supabase");
            }

            log.info("File uploaded successfully. Path: {}", filePath);

            return filePath;

        } catch (IOException e) {

            log.error("Failed to read uploaded file", e);

            throw new RuntimeException("Failed to read file content", e);

        } catch (RestClientException e) {

            log.error("Supabase Storage API call failed", e);

            throw new RuntimeException("Failed to upload file to Supabase", e);
        }
    }

    @Override
    public String createSignedUrl(String filePath, int expiresInSeconds) {

        String url = supabaseStorageConfig.getUrl()
                + "/storage/v1/object/sign/"
                + supabaseStorageConfig.getBucketName()
                + "/" + filePath;

        HttpHeaders headers = new HttpHeaders();

        headers.set("apikey", supabaseStorageConfig.getSecretKey());
        headers.set("Authorization", "Bearer " + supabaseStorageConfig.getSecretKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("expiresIn", expiresInSeconds);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<Map> response = supabaseRestTemplate.exchange(
                            url,
                            HttpMethod.POST,
                            requestEntity,
                            Map.class
            );

            Map responseBody = response.getBody();

            if (responseBody == null) {
                throw new RuntimeException("Failed to create signed URL: empty response");
            }

            Object signedUrlObject = responseBody.get("signedURL");

            if (signedUrlObject == null) {
                throw new RuntimeException("Failed to create signed URL");
            }

            String signedUrl = signedUrlObject.toString();

            if (signedUrl.startsWith("/")) {
                signedUrl = supabaseStorageConfig.getUrl()
                        + "/storage/v1"
                        + signedUrl;
            }

            return signedUrl;

        } catch (RestClientException e) {

            log.error("Failed to create signed URL for file: {}", filePath, e);

            throw new RuntimeException("Failed to create signed URL", e);
        }
    }

    private void validateFile(MultipartFile file) {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File must not be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size must not exceed 40 MB");
        }

        String contentType = file.getContentType();

        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("Only PDF, DOCX, PPTX and TXT files are allowed");
        }

        String filename = file.getOriginalFilename();

        if (!StringUtils.hasText(filename)) {
            throw new IllegalArgumentException("File name must not be empty");
        }
    }

    private String getFileExtension(String filename) {

        if (filename == null || filename.isBlank()) {
            return "";
        }

        int lastDotIndex = filename.lastIndexOf(".");

        if (lastDotIndex == -1) {
            return "";
        }

        return filename.substring(lastDotIndex).toLowerCase();
    }
}
