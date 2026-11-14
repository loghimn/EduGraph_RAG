package fu.coreservice.dto.admin;
import fu.coreservice.dto.activitylog.ActivityLogResponse;
import java.time.LocalDateTime;
import java.util.List;
public record AdminDashboardResponse(LocalDateTime generatedAt, long totalUsers, long students, long instructors,
        long admins, long enabledUsers, long disabledUsers, long pendingInstructors, long newUsersLast7Days,
        long courses, long notebooks, long documents, long completedDocuments, long failedDocuments,
        long failedEventsLast24Hours, List<AdminUserResponse> recentUsers, List<ActivityLogResponse> recentEvents) {}
