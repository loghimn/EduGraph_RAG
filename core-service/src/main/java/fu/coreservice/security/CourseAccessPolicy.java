package fu.coreservice.security;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import java.util.function.BooleanSupplier;

/** Pure authorization rules shared by course, notebook, chat and graph services. */
public final class CourseAccessPolicy {
    private CourseAccessPolicy() {}

    public static boolean isOwner(Course course, User user) {
        return course.getUser().getUserId().equals(user.getUserId());
    }

    public static void requireOwner(Course course, User user) {
        if (!isOwner(course, user)) throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
    }

    public static void requireReadAccess(Course course, User user, boolean enrolled) {
        requireReadAccess(course, user, () -> enrolled);
    }

    public static void requireReadAccess(Course course, User user, BooleanSupplier enrolled) {
        if (!isOwner(course, user) && (course.isNotebook() || !enrolled.getAsBoolean())) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }
    }

    public static void requireGraphWriteAccess(Course course, User user) {
        requireOwner(course, user);
        if (!course.isNotebook() && user.getRole() != UserRole.INSTRUCTOR) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }
    }
}
