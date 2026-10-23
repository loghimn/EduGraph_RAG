package fu.coreservice.service;

public interface PasswordResetMailer {
    void send(String email, String otp);
}
