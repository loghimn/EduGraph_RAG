package fu.coreservice.dto.chat;

public record CitationSourceResponse(Long documentId, String fileName, String downloadUrl, Integer pageNumber) {}
