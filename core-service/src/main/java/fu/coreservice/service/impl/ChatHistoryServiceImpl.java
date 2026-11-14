package fu.coreservice.service.impl;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.*;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import fu.coreservice.security.CourseAccessPolicy;
import fu.coreservice.service.ChatHistoryService;
import fu.coreservice.service.SupabaseStorageService;
import fu.coreservice.service.ActivityAlertService;
import fu.coreservice.utils.ClarificationCodec;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatHistoryServiceImpl implements ChatHistoryService {
    private final UserRepository users;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final MessageCitationRepository citations;
    private final MessageFeedbackRepository feedback;
    private final DocumentRepository documents;
    private final CourseRepository courses;
    private final CourseEnrollmentRepository enrollments;
    private final SupabaseStorageService storage;
    private final ActivityAlertService alerts;

    @Override
    public PageResponse<ChatSessionResponse> search(Long courseId, String query, int page, int size) {
        validatePage(page, size);
        if (query == null || query.length() > 255) throw new AppException(ErrorCode.INVALID_PARAMETER);
        User user = currentUser();
        requireScope(courseId, user);
        String pattern = "%" + query.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var result = sessions.searchOwned(user, courseId, pattern,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "lastActive", "sessionId")));
        return PageResponse.<ChatSessionResponse>builder().content(result.getContent().stream().map(this::toSession).toList())
                .page(page).size(size).totalElements(result.getTotalElements()).totalPages(result.getTotalPages())
                .first(result.isFirst()).last(result.isLast()).build();
    }

    @Override
    public ChatSessionResponse get(Long courseId, Long sessionId) { return toSession(owned(courseId, sessionId, false)); }

    @Override
    @Transactional
    public ChatSessionResponse rename(Long courseId, Long sessionId, String title) {
        if (title == null || title.isBlank() || title.length() > 255) throw new AppException(ErrorCode.INVALID_PARAMETER);
        ChatSession session = owned(courseId, sessionId, true);
        session.setTitle(title.strip());
        return toSession(sessions.saveAndFlush(session));
    }

    @Override
    @Transactional
    public void delete(Long courseId, Long sessionId) {
        ChatSession session = owned(courseId, sessionId, true);
        if (messages.existsBySessionAndStatus(session, ChatMessageStatus.STREAMING)) throw new AppException(ErrorCode.CHAT_SESSION_BUSY);
        citations.deleteSessionCitations(session);
        feedback.deleteSessionFeedback(session);
        messages.detachReplies(session);
        messages.deleteSessionMessages(session);
        sessions.delete(session);
        alerts.destructiveAfterCommit(session.getLearner().getUserId(), "CHAT_SESSION_DELETE", "CHAT_SESSION");
    }

    @Override
    public ChatMessageWindow recent(Long courseId, Long sessionId, Long beforeId, int size) {
        validatePage(0, size);
        if (beforeId != null && beforeId <= 0) throw new AppException(ErrorCode.INVALID_PARAMETER);
        ChatSession session = owned(courseId, sessionId, false);
        List<ChatMessage> rows = new ArrayList<>(messages.findBySessionAndMessageIdLessThanOrderByMessageIdDesc(
                session, beforeId == null ? Long.MAX_VALUE : beforeId, PageRequest.of(0, size + 1)));
        boolean hasOlder = rows.size() > size;
        if (hasOlder) rows.remove(rows.size() - 1);
        Collections.reverse(rows);
        List<Long> ids = rows.stream().map(ChatMessage::getMessageId).toList();
        List<MessageCitation> sourceRows = ids.isEmpty() ? List.of() : citations.findByMessage_MessageIdInOrderByCitationNumberAscCitationIdAsc(ids);
        Set<Long> docIds = sourceRows.stream().map(MessageCitation::getDocumentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Document> docs = new HashMap<>();
        if (!docIds.isEmpty()) documents.findAllById(docIds).forEach(doc -> { if (accessibleDocument(session, doc)) docs.put(doc.getDocumentId(), doc); });
        Map<Long, List<ChatCitationResponse>> byMessage = sourceRows.stream().collect(Collectors.groupingBy(citation -> citation.getMessage().getMessageId(),
                        Collectors.mapping(citation -> toCitation(citation, docs.get(citation.getDocumentId())), Collectors.toList())));
        List<ChatMessageResponse> result = rows.stream().map(message -> ChatMessageResponse.builder()
                .messageId(message.getMessageId()).replyToMessageId(message.getReplyToMessage() == null ? null : message.getReplyToMessage().getMessageId())
                .senderType(message.getSenderType()).content(message.getContent()).status(message.getStatus())
                .createdAt(message.getCreatedAt()).tokensUsed(message.getTokensUsed()).latencyMs(message.getLatencyMs())
                .clarificationCandidates(ClarificationCodec.decode(message.getClarificationOptions()))
                .citations(byMessage.getOrDefault(message.getMessageId(), List.of())).build()).toList();
        return new ChatMessageWindow(result, hasOlder, rows.isEmpty() ? null : rows.get(0).getMessageId());
    }

    @Override
    public CitationSourceResponse source(Long courseId, Long sessionId, Long citationId) {
        ChatSession session = owned(courseId, sessionId, false);
        MessageCitation citation = citations.findById(citationId)
                .filter(item -> item.getMessage().getSession().getSessionId().equals(sessionId))
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (citation.getDocumentId() == null) throw new AppException(ErrorCode.DOCUMENT_NOT_FOUND);
        Document doc = documents.findById(citation.getDocumentId()).filter(item -> accessibleDocument(session, item))
                .filter(item -> item.getDocStatus() == UploadStatus.COMPLETED)
                .orElseThrow(() -> new AppException(ErrorCode.DOCUMENT_NOT_FOUND));
        return new CitationSourceResponse(doc.getDocumentId(), doc.getFileName(), storage.createSignedUrl(doc.getFileUrlPath(), 300), citation.getPageNumber());
    }

    private boolean accessibleDocument(ChatSession session, Document document) {
        Course course = document.getCourse();
        if (session.getCourse() == null) return course.isPublic() && !course.isNotebook();
        return course.getCourseId().equals(session.getCourse().getCourseId());
    }

    private ChatCitationResponse toCitation(MessageCitation citation, Document doc) {
        return ChatCitationResponse.builder()
                .citationId(citation.getCitationId()).citationNumber(citation.getCitationNumber()).citationType(citation.getCitationType())
                .documentId(citation.getDocumentId()).vectorChunkId(citation.getVectorChunkId()).neo4jConceptId(citation.getNeo4jConceptId())
                .pageNumber(citation.getPageNumber()).chunkIndex(citation.getChunkIndex()).snippetText(citation.getSnippetText())
                .fileName(doc == null ? null : doc.getFileName()).courseId(doc == null ? null : doc.getCourse().getCourseId()).build();
    }

    private ChatSession owned(Long courseId, Long sessionId, boolean lock) {
        User user = currentUser();
        ChatSession session = (lock ? sessions.findOwnedForUpdate(sessionId, user) : sessions.findBySessionIdAndLearner(sessionId, user))
                .filter(item -> Objects.equals(item.getCourse() == null ? null : item.getCourse().getCourseId(), courseId))
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        requireScope(courseId, user);
        return session;
    }

    private void requireScope(Long courseId, User user) {
        if (courseId != null) {
            Course course = courses.findById(courseId).orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
            CourseAccessPolicy.requireReadAccess(course, user, () -> enrollments.existsByCourse_CourseIdAndUser(courseId, user));
        }
    }

    private User currentUser() {
        return users.findByEmail(SecurityUtils.getCurrentUserEmail()).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) throw new AppException(ErrorCode.INVALID_PARAMETER);
    }

    private ChatSessionResponse toSession(ChatSession session) {
        return ChatSessionResponse.builder().sessionId(session.getSessionId()).title(session.getTitle())
                .courseId(session.getCourse() == null ? null : session.getCourse().getCourseId())
                .planId(session.getPlan() == null ? null : session.getPlan().getPlanId())
                .startedAt(session.getStartedAt()).lastActive(session.getLastActive()).build();
    }
}
