package fu.coreservice.service.impl;

import fu.coreservice.dto.activitylog.ActivityLogRequest;
import fu.coreservice.dto.activitylog.ActivityLogFilter;
import fu.coreservice.dto.activitylog.ActivityLogResponse;
import fu.coreservice.dto.PageResponse;
import fu.coreservice.entity.ActivityLog;
import fu.coreservice.exception.AppException;
import fu.coreservice.exception.ErrorCode;
import fu.coreservice.repository.ActivityLogRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Objects;

@Service
@Slf4j
public class ActivityLogServiceImpl implements ActivityLogService {
    private final ActivityLogRepository logs;
    private final UserRepository users;
    private final TransactionTemplate transactions;

    public ActivityLogServiceImpl(ActivityLogRepository logs, UserRepository users,
                                  PlatformTransactionManager transactionManager) {
        this.logs = logs;
        this.users = users;
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(5);
    }

    @Override
    public boolean log(ActivityLogRequest request) {
        Objects.requireNonNull(request, "request is required");
        try {
            // Catch outside execute: commit failures must also stay isolated from the caller.
            transactions.executeWithoutResult(transaction -> logs.saveAndFlush(ActivityLog.builder()
                    .actor(request.actorId() == null ? null : users.getReferenceById(request.actorId()))
                    .actorType(request.actorType()).action(request.action()).status(request.status())
                    .resourceType(request.resourceType()).errorCode(request.errorCode())
                    .message(request.message()).durationMs(request.durationMs()).build()));
            return true;
        } catch (RuntimeException ex) {
            // Exception text and request contents may contain sensitive data; do not print them.
            log.warn("Activity log persistence failed (exceptionType={})", ex.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ActivityLogResponse> listLogs(ActivityLogFilter filter) {
        if (filter == null || filter.page() < 0 || filter.size() < 1 || filter.size() > 100
                || (long) filter.page() * filter.size() > Integer.MAX_VALUE
                || (filter.actorId() != null && filter.actorId() <= 0)
                || (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to()))
                || (filter.action() != null && filter.action().length() > 100)) {
            throw new AppException(ErrorCode.INVALID_PARAMETER);
        }
        String action = filter.action() == null || filter.action().isBlank() ? null
                : filter.action().strip().toUpperCase(Locale.ROOT);
        var page = logs.searchLogs(filter.from(), filter.to(), action, filter.status(), filter.actorType(),
                filter.actorId(), PageRequest.of(filter.page(), filter.size(),
                Sort.by(Sort.Direction.DESC, "createdAt", "logId")));
        return PageResponse.<ActivityLogResponse>builder()
                .content(page.getContent().stream().map(this::toResponse).toList())
                .page(page.getNumber()).size(page.getSize()).totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages()).first(page.isFirst()).last(page.isLast()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityLogResponse getLog(Long logId) {
        if (logId == null || logId <= 0) throw new AppException(ErrorCode.INVALID_PARAMETER);
        return logs.findById(logId).map(this::toResponse)
                .orElseThrow(() -> new AppException(ErrorCode.ACTIVITY_LOG_NOT_FOUND));
    }

    private ActivityLogResponse toResponse(ActivityLog entry) {
        var actor = entry.getActor();
        return new ActivityLogResponse(entry.getLogId(), entry.getCreatedAt(),
                actor == null ? null : actor.getUserId(), actor == null ? null : actor.getUsername(),
                entry.getActorType(), entry.getAction(), entry.getResourceType(), entry.getStatus(),
                entry.getErrorCode(), entry.getMessage(), entry.getDurationMs());
    }
}
