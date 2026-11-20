package fu.coreservice.service;

import fu.coreservice.dto.chat.ChatTurn;
import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class CourseLearningAnalyticsService {
    private final ConceptQueryRepository queries;
    private final ChatMessageRepository messages;
    private final CourseRepository courses;
    private final LearnerMasteryRepository masteries;
    private final CourseAiSettingsService access;

    public record Target(String id, String name, String courseId) {}
    public record ConceptStats(String conceptId, String name, long queries, long learners,
                               long repeatLearners, long negativeFeedback, long inProgress, long mastered) {}
    public record Report(int days, LocalDateTime generatedAt, List<ConceptStats> concepts) {}

    @Transactional
    public void record(ChatTurn turn, List<Target> targets, List<String> authorizedCourses) {
        if (targets.isEmpty() || queries.existsByMessage_MessageId(turn.assistantMessageId())) return;
        ChatMessage message = messages.findById(turn.assistantMessageId()).orElse(null);
        if (message == null || message.getStatus() != ChatMessageStatus.COMPLETED
                || !message.getSession().getSessionId().equals(turn.sessionId())
                || !message.getSession().getLearner().getUserId().equals(turn.userId())) return;
        Set<String> seen = new HashSet<>();
        for (Target target : targets) {
            if (!authorizedCourses.contains(target.courseId()) || !seen.add(target.id())) continue;
            queries.save(ConceptQuery.builder().message(message)
                    .course(courses.getReferenceById(Long.valueOf(target.courseId())))
                    .conceptId(target.id()).name(target.name()).build());
        }
    }

    @Transactional(readOnly = true)
    public Report report(Long courseId, int days) {
        access.requireManager(courseId);
        if (days < 1 || days > 365) throw new fu.coreservice.exception.AppException(fu.coreservice.exception.ErrorCode.INVALID_REQUEST);
        LocalDateTime now = LocalDateTime.now();
        Map<String, long[]> totals = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        for (var count : queries.counts(courseId, now.minusDays(days))) {
            long[] stats = totals.computeIfAbsent(count.getConceptId(), key -> new long[6]);
            stats[0] += count.getQueries(); stats[1]++; stats[2] += count.getQueries() > 1 ? 1 : 0;
            stats[3] += count.getNegativeFeedback(); names.put(count.getConceptId(), count.getName());
        }
        for (var count : masteries.courseStatusCounts(courseId)) {
            long[] stats = totals.computeIfAbsent(count.getConceptId(), key -> new long[6]);
            if (count.getStatus() == MasteryStatus.IN_PROGRESS) stats[4] = count.getTotal();
            if (count.getStatus() == MasteryStatus.MASTERED) stats[5] = count.getTotal();
        }
        List<ConceptStats> result = totals.entrySet().stream().map(e -> {
            long[] s = e.getValue();
            return new ConceptStats(e.getKey(), names.getOrDefault(e.getKey(), e.getKey()), s[0], s[1], s[2], s[3], s[4], s[5]);
        }).sorted(Comparator.comparingLong(ConceptStats::queries).reversed().thenComparing(ConceptStats::conceptId)).toList();
        return new Report(days, now, result);
    }
}
