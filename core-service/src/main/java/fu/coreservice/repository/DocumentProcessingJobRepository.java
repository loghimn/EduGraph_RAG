package fu.coreservice.repository;

import fu.coreservice.entity.DocumentProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentProcessingJobRepository extends JpaRepository<DocumentProcessingJob, Long> {
}
