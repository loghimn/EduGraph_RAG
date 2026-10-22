package fu.coreservice.dto.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
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
    private String message;

    private List<String> conceptIds;

    @Positive(message = "Reply message ID must be positive")
    private Long replyToMessageId;
}
