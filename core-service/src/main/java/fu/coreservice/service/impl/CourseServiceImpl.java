package fu.coreservice.service.impl;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.course.CourseCreateRequest;
import fu.coreservice.dto.course.CourseResponse;
import fu.coreservice.dto.course.CourseUpdateRequest;
import fu.coreservice.entity.Course;
import fu.coreservice.entity.User;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.CourseService;
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
public class CourseServiceImpl implements CourseService {

    private final CourseRepository courseRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public CourseResponse createCourse(CourseCreateRequest request) {
        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = Course.builder()
                .user(currentUser)
                .courseName(request.getCourseName().trim())
                .description(request.getDescription().trim())
                .domain(request.getDomain().trim())
                .language(request.getLanguage().trim())
                .isPublic(request.getIsPublic())
                .build();

        Course savedCourse = courseRepository.save(course);

        return mapToCourseResponse(savedCourse);
    }

    @Override
    @Transactional
    public CourseResponse updateCourse(Long courseId, CourseUpdateRequest request) {
        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = courseRepository.findByCourseIdAndUser(courseId, currentUser)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_ACCESS_DENIED));

        course.setCourseName(request.getCourseName().trim());
        course.setDescription(request.getDescription().trim());
        course.setDomain(request.getDomain().trim());
        course.setLanguage(request.getLanguage().trim());
        course.setPublic(request.getIsPublic());

        Course updatedCourse = courseRepository.save(course);

        return mapToCourseResponse(updatedCourse);
    }

    @Override
    @Transactional
    public void deleteCourse(Long courseId) {
        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = courseRepository.findByCourseIdAndUser(courseId, currentUser)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_ACCESS_DENIED));

        course.setPublic(false);

        courseRepository.save(course);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> getMyCourses(int page, int size, String sortBy, String sortDirection, Boolean isPublic) {

        Sort.Direction direction = sortDirection.equalsIgnoreCase("asc")
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(direction, sortBy)
        );

        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Page<Course> coursePage;

        if (isPublic == null) {
            coursePage = courseRepository.findByUser(currentUser, pageable);
        } else {
            coursePage = courseRepository.findByUserAndIsPublic(currentUser, isPublic, pageable);
        }

        return getCourseResponsePageResponse(coursePage);
    }

    @Override
    @Transactional(readOnly = true)
    public CourseResponse getCourseDetail(Long courseId) {

        String email = SecurityUtils.getCurrentUserEmail();

        User currentUser = userRepository.findByEmail(email)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        Course course = courseRepository.findByCourseIdAndUser(courseId, currentUser)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_ACCESS_DENIED));

        return mapToCourseResponse(course);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CourseResponse> searchPublicCourses(String keyword, String domain, String language, int page, int size, String sortBy, String sortDirection) {

        keyword = normalize(keyword);
        domain = normalize(domain);
        language = normalize(language);

        Sort.Direction direction =
                sortDirection.equalsIgnoreCase("asc")
                        ? Sort.Direction.ASC
                        : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(direction, sortBy)
        );

        Page<Course> coursePage = courseRepository.searchPublicCourses(
                keyword,
                domain,
                language,
                pageable
        );

        return getCourseResponsePageResponse(coursePage);
    }

    @Override
    @Transactional(readOnly = true)
    public CourseResponse getPublicCourseDetail(Long courseId) {
        Course course = courseRepository.findByCourseIdAndIsPublicTrue(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.PUBLIC_COURSE_NOT_FOUND));

        return mapToCourseResponse(course);
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

    private PageResponse<CourseResponse> getCourseResponsePageResponse(Page<Course> coursePage) {
        List<CourseResponse> courses = coursePage.getContent()
                .stream()
                .map(this::mapToCourseResponse)
                .toList();

        return PageResponse.<CourseResponse>builder()
                .content(courses)
                .page(coursePage.getNumber())
                .size(coursePage.getSize())
                .totalElements(coursePage.getTotalElements())
                .totalPages(coursePage.getTotalPages())
                .first(coursePage.isFirst())
                .last(coursePage.isLast())
                .build();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

}
