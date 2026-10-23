package fu.coreservice.service.impl;

import fu.coreservice.service.PasswordResetMailer;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PasswordResetMailerImpl implements PasswordResetMailer {
    private final JavaMailSender sender;
    @Value("${app.password-reset.from}") private String from;

    @Override
    public void send(String email, String otp) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("EduGraph - Reset your password");
        message.setText("Your EduGraph password reset OTP is: " + otp
                + "\nThis code expires in 5 minutes and can only be used once."
                + "\nDo not share this code. If you did not request this, ignore this email.");
        sender.send(message);
    }
}
