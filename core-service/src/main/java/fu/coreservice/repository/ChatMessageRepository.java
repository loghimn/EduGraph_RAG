package fu.coreservice.repository;

import fu.coreservice.entity.ChatMessage;
import fu.coreservice.entity.ChatSession;
import fu.coreservice.entity.SenderType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findBySession(ChatSession session);
    List<ChatMessage> findBySessionOrderByCreatedAtAsc(ChatSession session);
    List<ChatMessage> findBySenderType(SenderType senderType);
    List<ChatMessage> findBySession_SessionIdOrderByCreatedAtAsc(Long sessionId);
}
