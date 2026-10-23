package fu.coreservice.service;

public interface PasswordResetDispatcher {
    void dispatch(String email);
}
