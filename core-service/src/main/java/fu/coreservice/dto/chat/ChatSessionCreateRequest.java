package fu.coreservice.dto.chat;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChatSessionCreateRequest {
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;
}
