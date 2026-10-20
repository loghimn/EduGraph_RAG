package fu.coreservice.service.impl;

import fu.coreservice.dto.PageResponse;
import fu.coreservice.dto.chat.*;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.*;
import fu.coreservice.service.ChatSessionService;
import fu.coreservice.dto.request.ChatRequest;
import fu.coreservice.dto.ai.AiChatHistoryMessage;
import fu.coreservice.service.chat.ChatSource;
import fu.coreservice.service.chat.ChatTurn;
import java.time.LocalDateTime;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl implements ChatSessionService {
    private final UserRepository userRepository;
    private final CourseRepository courseRepository;
    private final CourseEnrollmentRepository courseEnrollmentRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final MessageCitationRepository messageCitationRepository;

    @Override
    @Transactional
    public ChatTurn beginTurn(Long courseId, Long sessionId, ChatRequest request) {
        CourseAccess access = requireCourseAccess(courseId);
        ChatSession session = chatSessionRepository.findBySessionIdAndCourseAndLearner(
                sessionId, access.course(), access.user())
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        List<AiChatHistoryMessage> history = loadContext(session);
        ChatMessage question = chatMessageRepository.saveAndFlush(ChatMessage.builder()
                .session(session).senderType(SenderType.USER).content(request.getMessage())
                .status(ChatMessageStatus.COMPLETED).build());
        ChatMessage answer = chatMessageRepository.saveAndFlush(ChatMessage.builder()
                .session(session).senderType(SenderType.BOT).content("")
                .status(ChatMessageStatus.STREAMING).build());
        session.setLastActive(LocalDateTime.now());
        chatSessionRepository.save(session);
        return new ChatTurn(sessionId, access.user().getUserId(), question.getMessageId(), answer.getMessageId(), history);
    }

    private List<AiChatHistoryMessage> loadContext(ChatSession session) {
        // Query only after ownership validation and before inserting the current turn.
        List<ChatMessage> recent = chatMessageRepository.findBySessionAndStatusOrderByCreatedAtDescMessageIdDesc(
                session, ChatMessageStatus.COMPLETED, PageRequest.of(0, 12));
        List<AiChatHistoryMessage> history = new ArrayList<>();
        int remaining = 12_000;
        for (ChatMessage message : recent) {
            if (remaining == 0) break;
            if (message.getContent() == null || message.getContent().isBlank()) continue;
            if (message.getSenderType() != SenderType.USER && message.getSenderType() != SenderType.BOT) continue;
            int limit = Math.min(Math.min(message.getContent().length(), 4_000), remaining);
            history.add(new AiChatHistoryMessage(message.getSenderType() == SenderType.USER ? "user" : "assistant",
                    message.getContent().substring(0, limit)));
            remaining -= limit;
        }
        Collections.reverse(history);
        return history;
    }

    @Override
    @Transactional
    public void finishTurn(ChatTurn turn, String content, ChatMessageStatus status, String error,
                           int latencyMs, List<ChatSource> sources, boolean clarification) {
        ChatMessage answer = chatMessageRepository.findById(turn.assistantMessageId())
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        if (!answer.getSession().getSessionId().equals(turn.sessionId())
                || answer.getStatus() != ChatMessageStatus.STREAMING) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        ChatSession session = answer.getSession();
        if (clarification) {
            chatMessageRepository.delete(answer);
        } else {
            answer.setContent(content);
            answer.setStatus(status);
            answer.setErrorMessage(error);
            answer.setLatencyMs(latencyMs);
            chatMessageRepository.save(answer);
            List<MessageCitation> citations = sources.stream().map(source -> MessageCitation.builder()
                    .message(answer).citationType(CitationType.ANSWER_CONCEPT)
                    .citationNumber(source.citationNumber()).documentId(source.documentId())
                    .vectorChunkId(source.chunkId()).pageNumber(source.pageNumber())
                    .chunkIndex(source.chunkIndex()).snippetText(source.text()).build()).toList();
            messageCitationRepository.saveAll(citations);
        }
        session.setLastActive(LocalDateTime.now());
        chatSessionRepository.save(session);
    }

    @Override
    @Transactional
    public ChatSessionResponse createSession(Long courseId, ChatSessionCreateRequest request) {
        CourseAccess access = requireCourseAccess(courseId);
        String title = request.getTitle();
        if (title != null && title.length() > 255) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        ChatSession session = ChatSession.builder()
                .learner(access.user()).course(access.course())
                .title(title == null || title.isBlank() ? "New conversation" : title.trim())
                .build();
        // Flush so Hibernate-generated timestamps are present in the response.
        return toSessionResponse(chatSessionRepository.saveAndFlush(session));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ChatSessionResponse> getMySessions(Long courseId, int page, int size) {
        validatePage(page, size);
        CourseAccess access = requireCourseAccess(courseId);
        Page<ChatSession> sessions = chatSessionRepository.findByLearnerAndCourse(
                access.user(), access.course(), PageRequest.of(page, size,
                        Sort.by(Sort.Direction.DESC, "lastActive", "sessionId")));
        return toPageResponse(sessions, this::toSessionResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ChatMessageResponse> getMessages(Long courseId, Long sessionId, int page, int size) {
        validatePage(page, size);
        CourseAccess access = requireCourseAccess(courseId);
        ChatSession session = chatSessionRepository.findBySessionIdAndCourseAndLearner(
                sessionId, access.course(), access.user())
                .orElseThrow(() -> new AppException(ErrorCode.CHAT_SESSION_NOT_FOUND));
        Page<ChatMessage> messages = chatMessageRepository.findBySession(session,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt", "messageId")));
        List<Long> ids = messages.getContent().stream().map(ChatMessage::getMessageId).toList();
        Map<Long, List<ChatCitationResponse>> citations = ids.isEmpty() ? Map.of()
                : messageCitationRepository.findByMessage_MessageIdInOrderByCitationNumberAscCitationIdAsc(ids)
                    .stream().collect(Collectors.groupingBy(c -> c.getMessage().getMessageId(),
                            Collectors.mapping(this::toCitationResponse, Collectors.toList())));
        return toPageResponse(messages, message -> ChatMessageResponse.builder()
                .messageId(message.getMessageId()).senderType(message.getSenderType())
                .content(message.getContent()).status(message.getStatus())
                .tokensUsed(message.getTokensUsed()).latencyMs(message.getLatencyMs())
                .createdAt(message.getCreatedAt())
                .citations(citations.getOrDefault(message.getMessageId(), List.of())).build());
    }

    private CourseAccess requireCourseAccess(Long courseId) {
        User user = userRepository.findByEmail(SecurityUtils.getCurrentUserEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new AppException(ErrorCode.COURSE_NOT_FOUND));
        if (!course.getUser().getUserId().equals(user.getUserId())
                && !courseEnrollmentRepository.existsByCourse_CourseIdAndUser(courseId, user)) {
            throw new AppException(ErrorCode.COURSE_ACCESS_DENIED);
        }
        return new CourseAccess(user, course);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new AppException(ErrorCode.INVALID_PARAMETER);
        }
    }

    private ChatSessionResponse toSessionResponse(ChatSession session) {
        return ChatSessionResponse.builder().sessionId(session.getSessionId())
                .courseId(session.getCourse().getCourseId())
                .planId(session.getPlan() == null ? null : session.getPlan().getPlanId())
                .title(session.getTitle()).startedAt(session.getStartedAt())
                .lastActive(session.getLastActive()).build();
    }

    private ChatCitationResponse toCitationResponse(MessageCitation citation) {
        return ChatCitationResponse.builder().citationId(citation.getCitationId())
                .citationNumber(citation.getCitationNumber()).citationType(citation.getCitationType())
                .documentId(citation.getDocumentId()).neo4jConceptId(citation.getNeo4jConceptId())
                .vectorChunkId(citation.getVectorChunkId()).pageNumber(citation.getPageNumber())
                .chunkIndex(citation.getChunkIndex()).snippetText(citation.getSnippetText()).build();
    }

    private <T, R> PageResponse<R> toPageResponse(Page<T> page, Function<T, R> mapper) {
        return PageResponse.<R>builder().content(page.getContent().stream().map(mapper).toList())
                .page(page.getNumber()).size(page.getSize()).totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages()).first(page.isFirst()).last(page.isLast()).build();
    }

    private record CourseAccess(User user, Course course) {}
}
