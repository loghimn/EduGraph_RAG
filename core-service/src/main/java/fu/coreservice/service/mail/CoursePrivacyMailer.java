package fu.coreservice.service.mail;

import fu.coreservice.entity.CoursePrivacyMail;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import jakarta.mail.MessagingException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.MimeMessageHelper;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CoursePrivacyMailer {
    private final JavaMailSender sender;
    @Value("${app.password-reset.from}") private String from;

    public void send(CoursePrivacyMail item) {
        var content = EmailTemplates.coursePrivacy(item.getCourseName());
        var message = sender.createMimeMessage();
        try {
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(from, "EduGraph-RAG");
            helper.setTo(item.getEmail());
            helper.setSubject(content.subject());
            helper.setText(content.text(), content.html());
        } catch (MessagingException | UnsupportedEncodingException error) {
            throw new MailPreparationException("Unable to prepare course privacy email", error);
        }
        sender.send(message);
    }
}
