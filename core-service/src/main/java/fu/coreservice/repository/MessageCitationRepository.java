package fu.coreservice.repository;

import fu.coreservice.entity.ChatMessage;
import fu.coreservice.entity.CitationType;
import fu.coreservice.entity.MessageCitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageCitationRepository extends JpaRepository<MessageCitation, Long> {
    List<MessageCitation> findByMessage(ChatMessage message);
    List<MessageCitation> findByMessage_MessageId(Long messageId);
    List<MessageCitation> findByCitationType(CitationType citationType);
}
