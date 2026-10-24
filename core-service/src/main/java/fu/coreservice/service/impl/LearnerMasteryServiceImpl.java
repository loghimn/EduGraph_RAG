package fu.coreservice.service.impl;

import fu.coreservice.service.LearnerMasteryService;

import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import fu.coreservice.exception.*;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@RequiredArgsConstructor
public class LearnerMasteryServiceImpl implements LearnerMasteryService {
    private final LearnerMasteryRepository masteries;
    private final LearnerMasteryHistoryRepository histories;
    private final UserRepository users;
    private final CourseRepository courses;
    private final CourseEnrollmentRepository enrollments;

    @Override
    @Transactional(readOnly = true)
    public List<MasteryView> list(Long courseId) {
        User user = currentUser(false);
        Course course = access(courseId, user);
        return masteries.findByLearnerAndCourse(user, course).stream().map(this::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> knownConcepts(Long courseId) {
        return list(courseId).stream().filter(m -> m.status() == MasteryStatus.MASTERED)
                .map(MasteryView::conceptId).sorted().toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryView> history(Long courseId, String conceptId) {
        User user = currentUser(false);
        Course course = access(courseId, user);
        return masteries.findByLearnerAndCourseAndNeo4jConceptId(user, course, conceptId)
                .map(m -> histories.findByMasteryOrderByChangedAtDesc(m).stream()
                        .map(h -> new HistoryView(h.getHistoryId(), h.getOldStatus(), h.getNewStatus(),
                                h.getChangedBy(), h.getChangedAt())).toList()).orElse(List.of());
    }

    // Serialize writes per learner, including creation of previously absent rows.
    @Override
    @Transactional
    public void initialize(Long courseId, Collection<String> conceptIds, Collection<String> knownIds) {
        User user = currentUser(true);
        Course course = access(courseId, user);
        Map<String, LearnerMastery> existing = new HashMap<>();
        masteries.findByLearnerAndCourse(user, course).forEach(m -> existing.put(m.getNeo4jConceptId(), m));
        for (String id : new LinkedHashSet<>(conceptIds)) {
            if (!existing.containsKey(id)) existing.put(id, change(user, course, id, null, MasteryStatus.NOT_STARTED));
        }
        for (String id : new LinkedHashSet<>(knownIds)) {
            change(user, course, id, existing.get(id), MasteryStatus.MASTERED);
        }
    }

    @Override
    @Transactional
    public MasteryView update(Long courseId, String conceptId, MasteryStatus status) {
        if (status == null) throw new AppException(ErrorCode.INVALID_REQUEST);
        User user = currentUser(true);
        Course course = access(courseId, user);
        // Only concepts already registered by a saved learning path can be edited directly.
        LearnerMastery mastery = masteries.findByLearnerAndCourseAndNeo4jConceptId(user, course, conceptId)
                .orElseThrow(() -> new AppException(ErrorCode.INVALID_REQUEST));
        return view(change(user, course, conceptId, mastery, status));
    }

    @Override
    @Transactional
    public void updateFromStep(Long courseId, String conceptId, PathStepStatus status) {
        User user = currentUser(true);
        Course course = access(courseId, user);
        LearnerMastery mastery = masteries.findByLearnerAndCourseAndNeo4jConceptId(user, course, conceptId).orElse(null);
        // Another learning path may already have completed this concept.
        if (mastery != null && mastery.getMasteryStatus() == MasteryStatus.MASTERED) return;
        MasteryStatus next = switch (status) {
            case COMPLETED -> MasteryStatus.MASTERED;
            case IN_PROGRESS -> MasteryStatus.IN_PROGRESS;
            default -> MasteryStatus.NOT_STARTED;
        };
        if (next == MasteryStatus.NOT_STARTED && mastery != null) return;
        change(user, course, conceptId, mastery, next);
    }

    private LearnerMastery change(User user, Course course, String id, LearnerMastery mastery, MasteryStatus status) {
        MasteryStatus old = mastery == null ? null : mastery.getMasteryStatus();
        if (old == status) return mastery;
        if (mastery == null) mastery = LearnerMastery.builder().learner(user).course(course).neo4jConceptId(id).build();
        mastery.setMasteryStatus(status);
        mastery.setIsSelfRated(true);
        mastery = masteries.saveAndFlush(mastery);
        histories.save(LearnerMasteryHistory.builder().mastery(mastery).oldStatus(old).newStatus(status)
                .changedBy("LEARNER").build());
        return mastery;
    }

    private MasteryView view(LearnerMastery m) {
        return new MasteryView(m.getNeo4jConceptId(), m.getMasteryStatus(), m.getIsSelfRated(), m.getUpdatedAt());
    }
    private User currentUser(boolean lock) {
        String email = SecurityUtils.getCurrentUserEmail();
        return (lock ? users.findLockedByEmail(email) : users.findByEmail(email))
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }
    private Course access(Long id, User user) {
        Course course = courses.findById(id).orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        if (!course.getUser().getUserId().equals(user.getUserId())
                && !enrollments.existsByCourse_CourseIdAndUser(id, user)) throw new AppException(ErrorCode.ACCESS_DENIED);
        return course;
    }
}
