package fu.coreservice.service.imp;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.course.CourseResponse;
import fu.coreservice.dto.courseEnrollment.CourseLearnerResponse;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.CourseEnrollment;
import fu.coreservice.entity.User;
import fu.coreservice.repository.CourseEnrollmentRepository;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.CourseEnrollmentService;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CourseEnrollmentServiceImpl implements CourseEnrollmentService {

    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void enrollCourse(Long courseId) {
        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Course course = courseRepository.findByCourseIdAndIsPublicTrue(courseId)
                .orElseThrow(() -> new RuntimeException("Public course not found"));

        boolean alreadyEnrolled = courseEnrollmentRepository.existsByCourseAndUser(course, currentUser);

        if (alreadyEnrolled) {
            throw new RuntimeException("You are already enrolled in this course");
        }

        CourseEnrollment enrollment = CourseEnrollment.builder()
                .course(course)
                .user(currentUser)
                .build();

        courseEnrollmentRepository.save(enrollment);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> getMyCoursesEnrollment(int page, int size, String sortBy, String sortDirection) {

        Sort.Direction direction = sortDirection.equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        Page<CourseEnrollment> enrollmentPage = courseEnrollmentRepository.findByUser(currentUser, pageable);

        List<CourseResponse> content = enrollmentPage.getContent()
                .stream()
                .map(enrollment -> mapToCourseResponse(enrollment.getCourse()))
                .toList();

        return PageResponse.<CourseResponse>builder()
                .content(content)
                .page(enrollmentPage.getNumber())
                .size(enrollmentPage.getSize())
                .totalElements(enrollmentPage.getTotalElements())
                .totalPages(enrollmentPage.getTotalPages())
                .first(enrollmentPage.isFirst())
                .last(enrollmentPage.isLast())
                .build();
    }

    @Override
    @Transactional
    public void leaveCourse(Long courseId) {
        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        CourseEnrollment enrollment = courseEnrollmentRepository.findByCourse_CourseIdAndUser(courseId, currentUser)
                .orElseThrow(() -> new RuntimeException("You are not enrolled in this course"));

        courseEnrollmentRepository.delete(enrollment);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CourseLearnerResponse> getCourseLearners(Long courseId, int page, int size, String sortBy, String sortDirection) {

        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // Only the course owner can access its learners
        Course course = courseRepository.findByCourseIdAndUser(courseId, currentUser)
                .orElseThrow(() -> new RuntimeException("Course not found"));

        Sort.Direction direction = sortDirection.equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(page, size, Sort.by(direction, sortBy));

        Page<CourseEnrollment> enrollmentPage = courseEnrollmentRepository.findByCourse(course, pageable);

        List<CourseLearnerResponse> content = enrollmentPage.getContent()
                .stream()
                .map(enrollment -> CourseLearnerResponse.builder()
                                .userId(enrollment.getUser().getUserId())
                                .username(enrollment.getUser().getUsername())
                                .email(enrollment.getUser().getEmail())
                                .enrollmentAt(enrollment.getEnrollmentAt())
                                .build())
                .toList();

        return PageResponse.<CourseLearnerResponse>builder()
                .content(content)
                .page(enrollmentPage.getNumber())
                .size(enrollmentPage.getSize())
                .totalElements(enrollmentPage.getTotalElements())
                .totalPages(enrollmentPage.getTotalPages())
                .first(enrollmentPage.isFirst())
                .last(enrollmentPage.isLast())
                .build();
    }

    private CourseResponse mapToCourseResponse(Course course) {
        return CourseResponse.builder()
                .courseId(course.getCourseId())
                .courseName(course.getCourseName())
                .description(course.getDescription())
                .domain(course.getDomain())
                .language(course.getLanguage())
                .isPublic(course.isPublic())
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .build();
    }

}
