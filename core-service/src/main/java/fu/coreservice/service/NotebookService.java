package fu.coreservice.service;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.notebook.*;
import fu.coreservice.entity.*;
import fu.coreservice.exception.*;
import fu.coreservice.repository.*;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotebookService {
 private final CourseRepository courseRepository;
 private final UserRepository userRepository;
 private User currentUser() {
  return userRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
   .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
 }
 public boolean owns(Long id) {
  return courseRepository.findByCourseIdAndUser(id, currentUser()).filter(Course::isNotebook).isPresent();
 }
 public boolean isNotebook(Long id) {
  return courseRepository.findById(id).map(Course::isNotebook).orElse(false);
 }
 public NotebookResponse get(Long id) {
  return courseRepository.findByCourseIdAndUser(id, currentUser()).filter(Course::isNotebook)
   .map(NotebookResponse::from).orElseThrow(() -> new AppException(ErrorCode.COURSE_ACCESS_DENIED));
 }
 @Transactional
 public NotebookResponse create(NotebookRequest r) {
  Course c = Course.builder().user(currentUser()).notebook(true).isPublic(false)
   .courseName(r.title().trim()).description(r.description() == null ? "" : r.description().trim())
   .domain(r.domain().trim()).language(r.language()).build();
  return NotebookResponse.from(courseRepository.save(c));
 }
 public PageResponse<NotebookResponse> list(int page, int size) {
  Page<Course> result = courseRepository.findByUserAndNotebookTrue(currentUser(),
   PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
  return PageResponse.<NotebookResponse>builder()
   .content(result.getContent().stream().map(NotebookResponse::from).toList())
   .page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements())
   .totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast()).build();
 }
}
