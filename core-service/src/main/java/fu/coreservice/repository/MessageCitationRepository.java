package fu.coreservice.repository;

import fu.coreservice.entity.ChatMessage;
import fu.coreservice.entity.CitationType;
import fu.coreservice.entity.MessageCitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageCitationRepository extends JpaRepository<MessageCitation, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from MessageCitation c where c.message.session = :session")
    void deleteSessionCitations(@org.springframework.data.repository.query.Param("session") fu.coreservice.entity.ChatSession session);
    void deleteByMessage_MessageId(Long messageId);
    List<MessageCitation> findByMessage_MessageIdInOrderByCitationNumberAscCitationIdAsc(List<Long> messageIds);
    List<MessageCitation> findByMessage(ChatMessage message);
    List<MessageCitation> findByMessage_MessageId(Long messageId);
    List<MessageCitation> findByCitationType(CitationType citationType);
}
