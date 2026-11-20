package fu.coreservice.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import fu.coreservice.dto.chat.CourseSuggestion;
import fu.coreservice.entity.User;
import fu.coreservice.repository.CourseRepository;
import fu.coreservice.repository.CourseEnrollmentRepository;
import java.util.*;
import java.util.stream.Collectors;

/** Suggestions survive reloads, while visibility, course names and enrollment remain current. */
public final class CourseSuggestionCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private CourseSuggestionCodec() {}
    public static String encode(List<CourseSuggestion> suggestions) {
        try { return JSON.writeValueAsString(suggestions); }
        catch (Exception error) { throw new IllegalArgumentException("Invalid course suggestions", error); }
    }
    public static List<CourseSuggestion> decode(String value) {
        if (value == null || value.isBlank()) return List.of();
        try { return JSON.readValue(value, new TypeReference<List<CourseSuggestion>>() {}); }
        catch (Exception error) { return List.of(); }
    }
    public static List<CourseSuggestion> visible(String value, CourseRepository courses, CourseEnrollmentRepository enrollments, User learner) {
        List<CourseSuggestion> rows = decode(value);
        if (rows.isEmpty()) return List.of();
        var current = courses.findPublicChatCatalogByIds(rows.stream().map(CourseSuggestion::courseId).toList())
                .stream().collect(Collectors.toMap(CourseRepository.PublicChatCourseRow::getCourseId, row -> row));
        var joined = new HashSet<>(enrollments.findCourseIdsByUser(learner));
        return rows.stream().filter(row -> current.containsKey(row.courseId())).map(row -> {
            var course = current.get(row.courseId());
            return new CourseSuggestion(row.courseId(), course.getCourseName(), joined.contains(row.courseId()) || course.getOwnerId().equals(learner.getUserId()), row.subject(),
                    row.conceptIds(), row.graphRelationship(), row.question());
        }).toList();
    }
}
