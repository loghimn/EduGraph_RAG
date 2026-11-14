package fu.coreservice.dto.chat;

import fu.coreservice.entity.CitationType;

public record ChatSource(int citationNumber, Long documentId, String chunkId,
                         Integer pageNumber, Integer chunkIndex, String text, CitationType citationType) {
    public ChatSource(int citationNumber, Long documentId, String chunkId, Integer pageNumber, Integer chunkIndex, String text) {
        this(citationNumber, documentId, chunkId, pageNumber, chunkIndex, text, CitationType.ANSWER_CONCEPT);
    }
}
