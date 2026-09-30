package fu.coreservice.dto.courseEnrollment;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CourseLearnerResponse {

    private Long userId;
    private String username;
    private String email;
    private LocalDateTime enrollmentAt;

}
