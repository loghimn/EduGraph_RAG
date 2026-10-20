package fu.coreservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_messages", indexes = {
        @Index(name = "idx_chat_messages_session_created", columnList = "session_id, created_at, message_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private ChatSession session;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private SenderType senderType;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    // Existing messages and ordinary user messages are completed by default.
    // The streaming service must explicitly create a BOT message as STREAMING.
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @ColumnDefault("'COMPLETED'")
    private ChatMessageStatus status = ChatMessageStatus.COMPLETED;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
