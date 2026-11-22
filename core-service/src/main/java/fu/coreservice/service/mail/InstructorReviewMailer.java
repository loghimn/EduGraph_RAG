package fu.coreservice.service.mail;

import fu.coreservice.event.InstructorReviewedEvent;
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
public class InstructorReviewMailer {
    private final JavaMailSender sender;
    // Reuse the sender address and SMTP configuration already used for account emails.
    @Value("${app.password-reset.from}") private String from;

    public void send(InstructorReviewedEvent event) {
        var content = EmailTemplates.instructorReview(event.username(), event.approved(),
                event.active(), event.rejectionReason());
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "EduGraph-RAG");
            helper.setTo(event.email());
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
        } catch (MessagingException | UnsupportedEncodingException error) {
            throw new MailPreparationException("Unable to prepare instructor review email", error);
        }
        sender.send(message);
    }
}
