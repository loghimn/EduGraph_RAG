package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.course.CourseCreateRequest;
import fu.coreservice.dto.course.CourseResponse;
import fu.coreservice.dto.course.CourseUpdateRequest;

public interface CourseService {

    /**
     * ROLE INSTRUCTOR
     */
    CourseResponse createCourse(CourseCreateRequest request);
    CourseResponse updateCourse(Long courseId, CourseUpdateRequest request);
    void deleteCourse(Long courseId);
    PageResponse<CourseResponse> getMyCourses(int page, int size, String sortBy, String sortDirection, Boolean isPublic);
    CourseResponse getCourseDetail(Long courseId);

    /**
     * ROLE STUDENT
     */
    PageResponse<CourseResponse> searchPublicCourses(String keyword, String domain, String language, int page, int size, String sortBy, String sortDirection);
    CourseResponse getPublicCourseDetail(Long courseId);

}
