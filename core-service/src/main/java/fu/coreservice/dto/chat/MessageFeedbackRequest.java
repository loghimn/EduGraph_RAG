package fu.coreservice.dto.chat;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MessageFeedbackRequest {
    @NotNull(message = "Rating is required")
    private Short rating;

    @Size(max = 2000, message = "Comment must not exceed 2000 characters")
    private String comment;

    @JsonIgnore
    @AssertTrue(message = "Rating must be 1 (helpful) or -1 (not helpful)")
    public boolean isRatingValid() {
        return rating == null || rating == 1 || rating == -1;
    }
}
