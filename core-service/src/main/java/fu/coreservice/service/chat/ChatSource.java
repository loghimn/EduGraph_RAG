package fu.coreservice.service.chat;

public record ChatSource(int citationNumber, Long documentId, String chunkId,
                         Integer pageNumber, Integer chunkIndex, String text) {}
