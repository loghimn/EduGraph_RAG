package fu.coreservice.repository;

import fu.coreservice.entity.DocumentProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DocumentProcessingJobRepository extends JpaRepository<DocumentProcessingJob, Long> {

    Optional<DocumentProcessingJob> findFirstByDocumentDocumentIdOrderByDocumentProcessingJobIdDesc(Long documentId);
}
