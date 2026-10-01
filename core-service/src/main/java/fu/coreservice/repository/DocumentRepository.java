package fu.coreservice.repository;

import fu.coreservice.entity.Document;
import fu.coreservice.entity.UploadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    Page<Document> findByCourseCourseId(Long courseId, Pageable pageable);
    Page<Document> findByCourseCourseIdAndDocStatus(Long courseId, UploadStatus docStatus, Pageable pageable);
    Optional<Document> findByDocumentIdAndCourseCourseId(Long documentId, Long courseId);

}
