package fu.coreservice.dto.chat;

import fu.coreservice.entity.CitationType;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChatCitationResponse {
    private Long citationId;
    private Integer citationNumber;
    private CitationType citationType;
    private Long documentId;
    private String neo4jConceptId;
    private String vectorChunkId;
    private Integer pageNumber;
    private Integer chunkIndex;
    private String snippetText;
}
