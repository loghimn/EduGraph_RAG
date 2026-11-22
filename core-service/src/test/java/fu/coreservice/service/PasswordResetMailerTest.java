package fu.coreservice.service;

import fu.coreservice.service.impl.PasswordResetMailerImpl;
import fu.coreservice.service.mail.EmailTemplates;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PasswordResetMailerTest {
    @Test void sendsUtf8HtmlAndPlainTextWithTheSameOtpWithoutResetLink() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        PasswordResetMailerImpl mailer = new PasswordResetMailerImpl(sender);
        ReflectionTestUtils.setField(mailer, "from", "sender@example.com");
        mailer.send("learner@example.com", "012345");
        ArgumentCaptor<MimeMessage> captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        var output = new java.io.ByteArrayOutputStream();
        captured.getValue().writeTo(output);
        // Verify the real MIME wire format, including non-ASCII text and alternatives.
        var message = new MimeMessage(Session.getInstance(new Properties()), new java.io.ByteArrayInputStream(output.toByteArray()));
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo("learner@example.com");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("sender@example.com");
        assertThat(from.getPersonal()).isEqualTo("EduGraph-RAG");
        assertThat(message.getSubject()).isEqualTo("EduGraph-RAG - Mã xác minh đặt lại mật khẩu");
        List<Part> alternatives = new ArrayList<>();
        collectTextParts(message, alternatives);
        assertThat(alternatives).hasSize(2);
        assertThat(alternatives.get(0).isMimeType("text/plain")).isTrue();
        assertThat(alternatives.get(1).isMimeType("text/html")).isTrue();
        for (Part part : alternatives) {
            assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
            assertThat((String) part.getContent()).contains("012345", "5 phút", "EduGraph-RAG").doesNotContain("token=", "{{");
        }
        assertThat((String) alternatives.get(0).getContent()).doesNotContain("http", "<html");
        assertThat((String) alternatives.get(1).getContent()).contains("lang=\"vi\"", "max-width:600px", "email-code").doesNotContain("<script", "<img", "href=");
    }

    @Test void escapesDynamicContentInsideHtml() {
        var content = EmailTemplates.passwordReset("<b>&");
        assertThat(content.html()).contains("&lt;b&gt;&amp;").doesNotContain("<b>");
        assertThat(content.text()).contains("<b>&");
    }

    @Test void mimePreparationFailureDoesNotSendAndRemainsAMailException() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var mailer = new PasswordResetMailerImpl(sender);
        ReflectionTestUtils.setField(mailer, "from", "sender@example.com");
        assertThatThrownBy(() -> mailer.send("invalid address", "012345")).isInstanceOf(MailPreparationException.class);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    private static void collectTextParts(Part part, List<Part> result) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart children = (Multipart) part.getContent();
            for (int i = 0; i < children.getCount(); i++) collectTextParts(children.getBodyPart(i), result);
        } else if (part.isMimeType("text/*")) result.add(part);
    }
}
