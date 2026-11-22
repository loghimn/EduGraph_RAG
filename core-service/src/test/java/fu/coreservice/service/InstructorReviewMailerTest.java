package fu.coreservice.service;

import fu.coreservice.event.InstructorReviewedEvent;
import fu.coreservice.service.mail.EmailTemplates;
import fu.coreservice.service.mail.InstructorReviewMailer;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InstructorReviewMailerTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void sendsReviewResultToInstructorInUtf8PlainTextAndHtml(boolean approved) throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var mailer = new InstructorReviewMailer(sender);
        ReflectionTestUtils.setField(mailer, "from", "sender@example.com");
        mailer.send(new InstructorReviewedEvent(7L, "teacher@example.com", "Nguyễn An", approved, true,
                approved ? null : "Hồ sơ chưa đầy đủ.\nVui lòng liên hệ quản trị viên."));

        var captured = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captured.capture());
        var output = new ByteArrayOutputStream();
        captured.getValue().writeTo(output);
        var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(output.toByteArray()));
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo("teacher@example.com");
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("sender@example.com");
        assertThat(message.getSubject()).contains(approved ? "đã được phê duyệt" : "Kết quả xét duyệt");
        List<Part> parts = new ArrayList<>();
        collectTextParts(message, parts);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).isMimeType("text/plain")).isTrue();
        assertThat(parts.get(1).isMimeType("text/html")).isTrue();
        for (Part part : parts) {
            assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
            String body = (String) part.getContent();
            assertThat(body).contains("Nguyễn An", approved ? "đã được quản trị viên phê duyệt" : "đã bị quản trị viên từ chối")
                    .doesNotContain("{{", "null", "password_hash", "token=");
            if (approved) assertThat(body).contains("Bạn có thể đăng nhập").doesNotContain("Lý do từ chối");
            else assertThat(body).contains("Lý do từ chối", "Hồ sơ chưa đầy đủ.", "Bạn chưa thể đăng nhập");
        }
    }

    @Test void rejectionWithoutReasonDoesNotInventOne() {
        for (String reason : new String[]{null, "", "   "}) {
            var content = EmailTemplates.instructorReview("An", false, true, reason);
            assertThat(content.text()).contains("bị quản trị viên từ chối").doesNotContain("Lý do từ chối", "null");
            assertThat(content.html()).doesNotContain("Lý do từ chối", "null");
        }
    }

    @Test void approvalOfLockedAccountDoesNotPromiseSignIn() {
        var content = EmailTemplates.instructorReview("An", true, false, "Old rejection reason");
        assertThat(content.text()).contains("đã được quản trị viên phê duyệt", "đang bị khóa")
                .doesNotContain("Bạn có thể đăng nhập", "Old rejection reason");
        assertThat(content.html()).contains("đang bị khóa").doesNotContain("Bạn có thể đăng nhập");
    }

    @Test void escapesUserContentAndDoesNotExpandTemplateMarkers() {
        var content = EmailTemplates.instructorReview("<b>{{heading}}</b>", false, true,
                "<script>alert('x')</script> & {{intro}}\nDòng hai");
        assertThat(content.html()).contains("&lt;b&gt;{{heading}}&lt;/b&gt;", "&lt;script&gt;", "&amp; {{intro}}")
                .doesNotContain("<script>", "<b>{{heading}}");
        assertThat(HtmlUtils.htmlUnescape(content.html())).contains("\nDòng hai");
        assertThat(content.text()).contains("<b>{{heading}}</b>", "<script>alert('x')</script>");
    }

    @Test void invalidRecipientDoesNotSend() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var mailer = new InstructorReviewMailer(sender);
        ReflectionTestUtils.setField(mailer, "from", "sender@example.com");
        assertThatThrownBy(() -> mailer.send(new InstructorReviewedEvent(7L, "invalid address", "An", true, true, null)))
                .isInstanceOf(MailPreparationException.class);
        verify(sender, never()).send(any(MimeMessage.class));
    }

    private static void collectTextParts(Part part, List<Part> result) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart children = (Multipart) part.getContent();
            for (int i = 0; i < children.getCount(); i++) collectTextParts(children.getBodyPart(i), result);
        } else if (part.isMimeType("text/*")) result.add(part);
    }
}
