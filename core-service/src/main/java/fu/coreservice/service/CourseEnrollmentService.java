package fu.coreservice.service;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.course.CourseResponse;
import fu.coreservice.dto.courseEnrollment.CourseLearnerResponse;

public interface CourseEnrollmentService {

    /**
     * ROLE STUDENT
     */
    void enrollCourse(Long courseId);
    PageResponse<CourseResponse> getMyCoursesEnrollment(int page, int size, String sortBy, String sortDirection);
    void leaveCourse(Long courseId);

    /**
     * ROLE INSTRUCTOR
     */
    PageResponse<CourseLearnerResponse> getCourseLearners(Long courseId, int page, int size, String sortBy, String sortDirection);
}
