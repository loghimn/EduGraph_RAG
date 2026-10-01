package fu.coreservice.dto.course;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CourseResponse {

    private Long courseId;

    private String courseName;

    private String description;

    private String domain;

    private String language;

    private Boolean isPublic;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
