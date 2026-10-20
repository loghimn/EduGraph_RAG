package fu.coreservice.repository;

import fu.coreservice.entity.ChatMessage;
import fu.coreservice.entity.ChatSession;
import fu.coreservice.entity.SenderType;
import fu.coreservice.entity.ChatMessageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    Optional<ChatMessage> findByMessageIdAndSession(Long messageId, ChatSession session);
    List<ChatMessage> findBySessionAndStatusOrderByCreatedAtDescMessageIdDesc(
            ChatSession session, ChatMessageStatus status, Pageable pageable);
    Page<ChatMessage> findBySession(ChatSession session, Pageable pageable);
    List<ChatMessage> findBySession(ChatSession session);
    List<ChatMessage> findBySessionOrderByCreatedAtAsc(ChatSession session);
    List<ChatMessage> findBySenderType(SenderType senderType);
    List<ChatMessage> findBySession_SessionIdOrderByCreatedAtAsc(Long sessionId);
}
