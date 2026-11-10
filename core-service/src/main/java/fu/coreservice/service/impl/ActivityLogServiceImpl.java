package fu.coreservice.service.impl;

import fu.coreservice.dto.activitylog.ActivityLogRequest;
import fu.coreservice.entity.ActivityLog;
import fu.coreservice.repository.ActivityLogRepository;
import fu.coreservice.repository.UserRepository;
import fu.coreservice.service.ActivityLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
}
