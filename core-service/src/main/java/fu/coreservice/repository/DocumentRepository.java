package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.DocStatus;
import fu.coreservice.entity.Document;
import fu.coreservice.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByCourse(Course course);
    Page<Document> findByCourse(Course course, Pageable pageable);
    List<Document> findByUploadedBy(User uploadedBy);
    List<Document> findByUploadStatus(DocStatus uploadStatus);
    List<Document> findByCourse_CourseIdAndUploadStatus(Long courseId, DocStatus uploadStatus);
}
