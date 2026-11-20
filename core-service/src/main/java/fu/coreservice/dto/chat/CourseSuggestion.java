package fu.coreservice.dto.chat;

import java.util.List;

public record CourseSuggestion(Long courseId, String courseName, boolean enrolled, String subject,
                               List<String> conceptIds, ChatGraphRelationship graphRelationship, String question) {}
