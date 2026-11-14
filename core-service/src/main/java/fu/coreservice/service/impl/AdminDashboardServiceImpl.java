package fu.coreservice.service.impl;
import fu.coreservice.dto.admin.*;
import fu.coreservice.dto.activitylog.ActivityLogFilter;
import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import fu.coreservice.exception.*;
import fu.coreservice.service.*;
import fu.coreservice.utils.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdminDashboardServiceImpl implements AdminDashboardService {
    private final UserRepository users;
    private final CourseRepository courses;
    private final DocumentRepository documents;
    private final ActivityLogRepository activityLogs;
    private final ActivityLogService logs;

    @Override @Transactional(readOnly = true)
    public AdminDashboardResponse overview() {
        users.findByEmail(SecurityUtils.getCurrentUserEmail())
                .filter(user -> user.getRole() == UserRole.ADMIN && Boolean.TRUE.equals(user.getIsActive()))
                .orElseThrow(() -> new AppException(ErrorCode.ACCESS_DENIED));
        long total = 0, students = 0, instructors = 0, admins = 0, enabled = 0, disabled = 0, pending = 0;
        for (var population : users.population()) {
            long count = population.getCount(); total += count;
            if (population.getRole() == UserRole.STUDENT) students += count;
            else if (population.getRole() == UserRole.INSTRUCTOR) instructors += count;
            else if (population.getRole() == UserRole.ADMIN) admins += count;
            if (Boolean.TRUE.equals(population.getActive())) enabled += count; else disabled += count;
            if (population.getRole() == UserRole.INSTRUCTOR && population.getApproval() == ApprovalStatus.PENDING) pending += count;
        }
        var now = LocalDateTime.now();
        return new AdminDashboardResponse(now, total, students, instructors, admins, enabled, disabled, pending,
                users.countByCreatedAtGreaterThanEqual(now.minusDays(7)), courses.countByNotebook(false), courses.countByNotebook(true),
                documents.count(), documents.countByDocStatus(UploadStatus.COMPLETED), documents.countByDocStatus(UploadStatus.FAILED),
                activityLogs.countByStatusAndCreatedAtGreaterThanEqual(ActivityLogStatus.FAILED, now.minusHours(24)),
                users.findAll(PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt", "userId"))).map(AdminUserResponse::from).getContent(),
                logs.listLogs(new ActivityLogFilter(0, 5, null, null, null, null, null, null)).getContent());
    }
}
