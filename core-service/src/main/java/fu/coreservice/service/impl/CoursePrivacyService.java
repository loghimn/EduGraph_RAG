package fu.coreservice.service.impl;

import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CoursePrivacyService {
    private final CourseEnrollmentRepository enrollments;
    private final CoursePrivacyMailRepository mail;

    /** Caller holds the course write lock, also used by enrollment creation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void makePrivate(Course course) {
        for (CourseEnrollment enrollment : enrollments.findByCourse(course)) {
            mail.save(CoursePrivacyMail.builder().email(enrollment.getUser().getEmail())
                    .courseName(course.getCourseName()).nextAttemptAt(LocalDateTime.now()).build());
            enrollments.delete(enrollment);
        }
        course.setPublic(false);
    }
}
