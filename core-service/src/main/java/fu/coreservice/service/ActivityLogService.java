package fu.coreservice.service;

import fu.coreservice.dto.activitylog.ActivityLogRequest;
import fu.coreservice.dto.activitylog.ActivityLogFilter;
import fu.coreservice.dto.activitylog.ActivityLogResponse;
import fu.coreservice.dto.PageResponse;

public interface ActivityLogService {
    /**
     * Records one final outcome in an independent transaction.
     * Returns false on persistence failure so callers can observe loss without failing their operation.
     * actorId must identify an already committed user, or be null for anonymous/system actions.
     */
    boolean log(ActivityLogRequest request);

    PageResponse<ActivityLogResponse> listLogs(ActivityLogFilter filter);

    ActivityLogResponse getLog(Long logId);
}
