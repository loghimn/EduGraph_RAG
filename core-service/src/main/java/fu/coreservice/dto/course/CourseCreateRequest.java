package fu.coreservice.dto.course;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseCreateRequest {

    @NotBlank(message = "Course name is required")
    @Size(
            min = 2,
            max = 150,
            message = "Course name must be between 2 and 150 characters"
    )
    private String courseName;

    @NotBlank(message = "Description is required")
    @Size(
            max = 2000,
            message = "Description must not exceed 2000 characters"
    )
    private String description;

    @NotBlank(message = "Domain is required")
    @Size(
            max = 100,
            message = "Domain must not exceed 100 characters"
    )
    private String domain;

    @NotBlank(message = "Language is required")
    @Size(
            max = 50,
            message = "Language must not exceed 50 characters"
    )
    private String language;

    @NotNull(message = "isPublic is required")
    private Boolean isPublic;

}
