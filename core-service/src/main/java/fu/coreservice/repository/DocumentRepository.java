package fu.coreservice.repository;

import fu.coreservice.entity.Document;
import fu.coreservice.entity.UploadStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.documentId = :id")
    Optional<Document> findForUpdate(@Param("id") Long documentId);

    Page<Document> findByCourseCourseId(Long courseId, Pageable pageable);
    Page<Document> findByCourseCourseIdAndDocStatus(Long courseId, UploadStatus docStatus, Pageable pageable);
    Optional<Document> findByDocumentIdAndCourseCourseId(Long documentId, Long courseId);
    List<Document> findByDocumentIdInAndCourseCourseId(List<Long> documentIds, Long courseId);
    List<Document> findByDocStatus(UploadStatus docStatus);

}
