package fu.coreservice.repository;

import fu.coreservice.entity.ChatMessage;
import fu.coreservice.entity.MessageFeedback;
import fu.coreservice.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedback, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from MessageFeedback f where f.message.session = :session")
    void deleteSessionFeedback(@org.springframework.data.repository.query.Param("session") fu.coreservice.entity.ChatSession session);
    Optional<MessageFeedback> findByMessage(ChatMessage message);
    List<MessageFeedback> findByUser(User user);
    Optional<MessageFeedback> findByMessage_MessageId(Long messageId);
}
