package fu.coreservice.service.impl;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.document.DocumentDownloadResponse;
import fu.coreservice.dto.document.DocumentResponse;
import fu.coreservice.entity.*;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.DocumentProcessingJobRepository;
import fu.coreservice.repository.DocumentRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.DocumentService;
import fu.coreservice.service.SupabaseStorageService;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.service.SecurityService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements DocumentService {

    private final DocumentRepository documentRepository;
    private final DocumentProcessingJobRepository documentProcessingJobRepository;
    private final CourseRepository courseRepository;
    private final SupabaseStorageService supabaseStorageService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public DocumentResponse uploadDocument(Long courseId, MultipartFile file) {

        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found"));

        if (!course.getUser().getUserId().equals(currentUser.getUserId())) {
            throw new RuntimeException("You are not the owner of this course");
        }

        if (file == null || file.isEmpty()) {
            throw new RuntimeException("File is null or empty");
        }

        Document document = Document.builder()
                .course(course)
                .user(currentUser)
                .fileName(file.getOriginalFilename())
                .fileType(file.getContentType())
                .fileSizeKb(file.getSize() / 1024)
                .docStatus(UploadStatus.PENDING)
                .build();
        document = documentRepository.save(document);

        String storagePath;
        try {
            storagePath = supabaseStorageService.uploadCourseMaterial(file, courseId, document.getDocumentId());
        } catch (Exception e) {
            log.error("Failed to upload document to Supabase. documentId={}", document.getDocumentId(), e);
            documentRepository.delete(document);
            throw e;
        }

        document.setFileUrlPath(storagePath);
        document = documentRepository.save(document);

        DocumentProcessingJob processingJob = DocumentProcessingJob.builder()
                        .document(document)
                        .jobType(JobType.FULL_PIPELINE)
                        .jobStatus(JobStatus.QUEUED)
                        .build();
        documentProcessingJobRepository.save(processingJob);
        return mapToResponse(document);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DocumentResponse> getDocumentsByCourse(Long courseId, int page, int size, UploadStatus status, Sort.Direction sortDirection) {

        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + courseId));

        Pageable pageable = PageRequest.of(page, size, Sort.by(sortDirection, "uploadAt"));

        Page<Document> documentPage;

        if (status != null) {
            documentPage = documentRepository.findByCourseCourseIdAndDocStatus(course.getCourseId(), status, pageable);
        } else {
            documentPage = documentRepository.findByCourseCourseId(course.getCourseId(), pageable);
        }

        List<DocumentResponse> content = documentPage.getContent()
                .stream()
                .map(this::mapToResponse)
                .toList();

        return PageResponse.<DocumentResponse>builder()
                .content(content)
                .page(documentPage.getNumber())
                .size(documentPage.getSize())
                .totalElements(documentPage.getTotalElements())
                .totalPages(documentPage.getTotalPages())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponse getDocumentById(Long courseId, Long documentId) {

        courseRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + courseId));

        Document document = documentRepository.findByDocumentIdAndCourseCourseId(documentId,courseId)
                .orElseThrow(() -> new RuntimeException("Document not found with id: " + documentId));

        return mapToResponse(document);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentDownloadResponse downloadDocument(Long courseId, Long documentId) {
        courseRepository.findById(courseId)
                .orElseThrow(() -> new RuntimeException("Course not found with id: " + courseId));

        Document document = documentRepository.findByDocumentIdAndCourseCourseId(documentId, courseId)
                .orElseThrow(() -> new RuntimeException("Document not found with id: " + documentId));

        String signedUrl = supabaseStorageService.createSignedUrl(document.getFileUrlPath(), 3600);

        return DocumentDownloadResponse.builder()
                .documentId(document.getDocumentId())
                .fileName(document.getFileName())
                .downloadUrl(signedUrl)
                .build();
    }

    private DocumentResponse mapToResponse(Document document) {

        return DocumentResponse.builder()
                .documentId(document.getDocumentId())
                .courseId(document.getCourse().getCourseId())
                .uploadedBy(document.getUser().getUserId())
                .fileName(document.getFileName())
                .fileType(document.getFileType())
                .fileSizeKb(document.getFileSizeKb())
                .docStatus(document.getDocStatus())
                .uploadAt(document.getUploadAt())
                .parseAt(document.getParseAt())
                .build();
    }
}
