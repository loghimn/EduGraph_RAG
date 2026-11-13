package fu.coreservice.service.impl;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.notebook.NotebookRequest;
import fu.coreservice.dto.notebook.NotebookResponse;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.NotebookService;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("notebookService")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotebookServiceImpl implements NotebookService {
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;
    private User currentUser() {
        return userRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
            .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }
    @Override
    public boolean owns(Long id) {
        return courseRepository.findByCourseIdAndUser(id, currentUser()).filter(Course::isNotebook).isPresent();
    }
    @Override
    public boolean isNotebook(Long id) {
        return courseRepository.findById(id).map(Course::isNotebook).orElse(false);
    }
    @Override
    public NotebookResponse get(Long id) {
        return courseRepository.findByCourseIdAndUser(id, currentUser()).filter(Course::isNotebook)
            .map(NotebookResponse::from).orElseThrow(() -> new AppException(ErrorCode.COURSE_ACCESS_DENIED));
    }
    @Transactional
    @Override
    public NotebookResponse create(NotebookRequest r) {
        Course c = Course.builder().user(currentUser()).notebook(true).isPublic(false)
            .courseName(r.title().trim()).description(r.description() == null ? "" : r.description().trim())
            .domain(r.domain().trim()).language(r.language()).build();
        return NotebookResponse.from(courseRepository.save(c));
    }
    @Override
    public PageResponse<NotebookResponse> list(int page, int size) {
        Page<Course> result = courseRepository.findByUserAndNotebookTrue(currentUser(),
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.<NotebookResponse>builder()
            .content(result.getContent().stream().map(NotebookResponse::from).toList())
            .page(result.getNumber()).size(result.getSize()).totalElements(result.getTotalElements())
            .totalPages(result.getTotalPages()).first(result.isFirst()).last(result.isLast()).build();
    }
}
