package fu.coreservice.service;

/** Records security, destructive, privileged and operational alerts. */
public interface ActivityAlertService {
    void accessDenied(String resourceType);
    void loginFailed(String email);
    void loginSucceeded(String email);
    void otpLimitReached(Long userId);
    void destructiveAfterCommit(Long actorId, String action, String resourceType);
    void privilegedChange(String action, String resourceType);
    void critical(String action, String resourceType, String errorCode);
    void repeatedSystemFailure(String operation, String resourceType);
}
