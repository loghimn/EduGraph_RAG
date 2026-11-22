package fu.coreservice.service;

import fu.coreservice.dto.ai.CourseAiSettings;
import fu.coreservice.entity.*;
import fu.coreservice.exception.*;
import fu.coreservice.repository.*;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CourseAiSettingsService {
    private final SystemSettingsRepository settings;
    private final CourseRepository courses;
    private final UserRepository users;

    public void requireManager(Long courseId) { manager(courseId); }

    public record CourseOption(Long courseId, String courseName, Long instructorId,
                               String instructorName, String instructorEmail, boolean isPublic) {}

    @Transactional(readOnly = true)
    public fu.coreservice.dto.PageResponse<CourseOption> listCourses(int page) {
        User user = requireAdmin();
        if (page < 0) throw new AppException(ErrorCode.INVALID_REQUEST);
        var result = courses.findTeachingCourses(user.getUserId(), true,
                org.springframework.data.domain.PageRequest.of(page, 50, org.springframework.data.domain.Sort.by("courseId")));
        return fu.coreservice.dto.PageResponse.<CourseOption>builder()
                .content(result.map(c -> new CourseOption(c.getCourseId(), c.getCourseName(),
                        c.getUser().getUserId(), c.getUser().getUsername(), c.getUser().getEmail(), c.isPublic())).getContent())
                .page(page).size(50).totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .first(result.isFirst()).last(result.isLast()).build();
    }

    private User requireAdmin() {
        User user = users.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !user.isApprovalSatisfied() || user.getRole() != UserRole.ADMIN)
            throw new AppException(ErrorCode.ACCESS_DENIED);
        return user;
    }

    private User settingsAdmin(Long courseId) {
        User actor = requireAdmin();
        Course course = courses.findById(courseId).orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        if (course.isNotebook()) throw new AppException(ErrorCode.ACCESS_DENIED);
        return actor;
    }

    private User manager(Long courseId) {
        User user = users.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Course course = courses.findById(courseId).orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        if (!Boolean.TRUE.equals(user.getIsActive()) || !user.isApprovalSatisfied() || !(user.getRole() == UserRole.ADMIN
                || user.getRole() == UserRole.INSTRUCTOR && course.getUser().getUserId().equals(user.getUserId()))) {
            throw new AppException(ErrorCode.ACCESS_DENIED);
        }
        return user;
    }

    @Transactional(readOnly = true)
    public CourseAiSettings get(Long courseId) {
        settingsAdmin(courseId);
        return settings.findByCourse_CourseId(courseId).map(this::view).orElse(CourseAiSettings.defaults());
    }

    @Transactional
    public CourseAiSettings save(Long courseId, CourseAiSettings request) {
        User actor = settingsAdmin(courseId);
        // Serialize first-time creation as well as updates for the same course.
        Course course = courses.findLockedSettingsCourse(courseId).orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        SystemSettings row = settings.findByCourse_CourseId(courseId).orElseGet(() -> SystemSettings.builder().course(course).build());
        row.setSimilarityThreshold(request.similarityThreshold());
        row.setTraversalDepth(request.traversalDepth());
        row.setPromptTemplate(request.promptTemplate().trim());
        row.setUpdatedBy(actor);
        return view(settings.saveAndFlush(row));
    }

    /** Called only with the scope already authorized by ChatSessionService. */
    @Transactional(readOnly = true)
    public Map<String, CourseAiSettings> forChat(List<String> authorizedIds) {
        if (authorizedIds.isEmpty()) return Map.of();
        Map<String, CourseAiSettings> result = new LinkedHashMap<>();
        settings.findByCourse_CourseIdIn(authorizedIds.stream().map(Long::valueOf).toList())
                .forEach(row -> result.put(row.getCourse().getCourseId().toString(), view(row)));
        return result;
    }

    private CourseAiSettings view(SystemSettings row) {
        return new CourseAiSettings(row.getSimilarityThreshold(), row.getTraversalDepth(),
                row.getPromptTemplate() == null ? "" : row.getPromptTemplate());
    }
}
