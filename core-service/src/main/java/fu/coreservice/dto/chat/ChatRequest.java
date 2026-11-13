package fu.coreservice.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request chat từ frontend; Java map sang AiChatRequest (snake_case) gọi Python /chat (SSE).
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @NotBlank(message = "Message must not be empty")
    @Size(max = 12000)
    private String message;

    @Size(max = 20)
    private List<@NotBlank @Size(max = 200) String> conceptIds;

    @Valid
    private ChatGraphRelationship graphRelationship;

    @Positive(message = "Reply message ID must be positive")
    private Long replyToMessageId;
}
