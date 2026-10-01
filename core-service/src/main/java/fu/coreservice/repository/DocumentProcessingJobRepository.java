package fu.coreservice.repository;

import fu.coreservice.entity.Document;
import fu.coreservice.entity.DocumentProcessingJob;
import fu.coreservice.entity.JobStatus;
import fu.coreservice.entity.JobType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentProcessingJobRepository extends JpaRepository<DocumentProcessingJob, Long> {
    List<DocumentProcessingJob> findByDocument(Document document);
    List<DocumentProcessingJob> findByDocument_DocumentId(Long documentId);
    Page<DocumentProcessingJob> findByDocument(Document document, Pageable pageable);
    List<DocumentProcessingJob> findByStatus(JobStatus status);
    List<DocumentProcessingJob> findByJobType(JobType jobType);
}
