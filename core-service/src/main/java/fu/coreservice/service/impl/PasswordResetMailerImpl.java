package fu.coreservice.service.impl;

import fu.coreservice.service.PasswordResetMailer;
import fu.coreservice.service.mail.EmailTemplates;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class PasswordResetMailerImpl implements PasswordResetMailer {
    private final JavaMailSender sender;
    @Value("${app.password-reset.from}") private String from;

    @Override
    public void send(String email, String otp) {
        var content = EmailTemplates.passwordReset(otp);
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "EduGraph-RAG");
            helper.setTo(email);
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
        } catch (MessagingException | UnsupportedEncodingException error) {
            throw new MailPreparationException("Unable to prepare password reset email", error);
        }
        sender.send(message);
    }
}
