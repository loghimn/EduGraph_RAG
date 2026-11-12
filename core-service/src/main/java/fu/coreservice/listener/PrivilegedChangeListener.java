package fu.coreservice.listener;

import fu.coreservice.entity.SystemSettings;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import fu.coreservice.service.ActivityAlertService;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Defers database writes until commit; never records secrets or configuration values. */
@Component
@Slf4j
public class PrivilegedChangeListener {
    @Autowired
    private ObjectProvider<ActivityAlertService> alerts;

    public PrivilegedChangeListener() {} // Standalone Hibernate may run without a Spring bean container.

    public PrivilegedChangeListener(ObjectProvider<ActivityAlertService> alerts) { this.alerts = alerts; }

    @PostLoad
    public void loaded(Object entity) {
        if (entity instanceof User user) user.capturePrivilegeSnapshot();
    }

    @PostPersist
    public void created(Object entity) {
        if (entity instanceof User user) {
            if (user.getRole() == UserRole.ADMIN) signal("ADMIN_ACCOUNT_CREATED", "USER");
            user.capturePrivilegeSnapshot();
        } else if (entity instanceof SystemSettings) signal("SYSTEM_SETTINGS_CREATED", "SYSTEM_SETTINGS");
    }

    @PostUpdate
    public void updated(Object entity) {
        if (entity instanceof User user) {
            if (user.isPrivilegeSnapshotLoaded() && (user.getPrivilegeSnapshotRole() != user.getRole()
                    || !Objects.equals(user.getPrivilegeSnapshotActive(), user.getIsActive())
                    || user.getPrivilegeSnapshotApprovalStatus() != user.getApprovalStatus())) {
                signal("USER_PRIVILEGE_CHANGED", "USER");
            }
            user.capturePrivilegeSnapshot();
        } else if (entity instanceof SystemSettings) signal("SYSTEM_SETTINGS_CHANGED", "SYSTEM_SETTINGS");
    }

    private void signal(String action, String resourceType) {
        try {
            if (alerts != null) alerts.getObject().privilegedChange(action, resourceType);
        } catch (RuntimeException ex) {
            log.warn("Unable to schedule privileged change alert (exceptionType={})", ex.getClass().getSimpleName());
        }
    }
}
