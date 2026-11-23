package fu.coreservice.service;

import fu.coreservice.entity.CoursePrivacyMail;
import fu.coreservice.repository.CoursePrivacyMailRepository;
import fu.coreservice.service.mail.*;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import jakarta.mail.Session;
import jakarta.mail.Part;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.InternetAddress;
import java.util.Properties;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.LocalDateTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CoursePrivacyMailDeliveryTest {
    @Test void failedDeliveryRemainsQueuedWhileOtherRecipientsAreDelivered() {
        var queue = mock(CoursePrivacyMailRepository.class);
        var mailer = mock(CoursePrivacyMailer.class);
        var failed = CoursePrivacyMail.builder().id(1L).email("failed@example.invalid")
                .courseName("Java").nextAttemptAt(LocalDateTime.now().minusMinutes(1)).build();
        var delivered = CoursePrivacyMail.builder().id(2L).email("ok@example.invalid")
                .courseName("Java").nextAttemptAt(LocalDateTime.now().minusMinutes(1)).build();
        when(queue.findTop20ByNextAttemptAtLessThanEqualOrderByIdAsc(any())).thenReturn(List.of(failed, delivered));
        doThrow(new MailSendException("Unavailable")).when(mailer).send(failed);
        new CoursePrivacyMailDelivery(queue, mailer).deliver();
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isAfter(LocalDateTime.now());
        verify(queue, never()).delete(failed);
        verify(queue).delete(delivered);
        verify(mailer).send(delivered);
    }

    @Test void emailExplainsRemovalAndPreservedHistoryToOnlyItsRecipient() throws Exception {
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        var mailer = new CoursePrivacyMailer(sender);
        ReflectionTestUtils.setField(mailer, "from", "noreply@example.invalid");
        var item = CoursePrivacyMail.builder().email("student@example.invalid").courseName("Cơ sở dữ liệu").build();
        mailer.send(item);
        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(captor.capture());
        var output = new ByteArrayOutputStream();
        captor.getValue().writeTo(output);
        var message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(output.toByteArray()));
        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo(item.getEmail());
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("EduGraph-RAG");
        List<Part> parts = new ArrayList<>();
        collectTextParts(message, parts);
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0).isMimeType("text/plain")).isTrue();
        assertThat(parts.get(1).isMimeType("text/html")).isTrue();
        for (Part part : parts) {
            assertThat(part.getContentType()).containsIgnoringCase("charset=UTF-8");
            assertThat((String) part.getContent()).contains(item.getCourseName(), "không thể gửi câu hỏi mới")
                    .doesNotContain("{{courseName}}", "{{content}}");
        }
    }

    @Test void courseNamesAreEscapedAndCannotInjectMarkupOrTemplateContent() {
        var content = EmailTemplates.coursePrivacy("<script>alert('x')</script> & {{heading}}");
        assertThat(content.html()).contains("&lt;script&gt;", "&amp; {{heading}}").doesNotContain("<script>");
        assertThat(content.text()).contains("<script>alert('x')</script> & {{heading}}");
    }

    private static void collectTextParts(Part part, List<Part> result) throws Exception {
        if (part.isMimeType("multipart/*")) {
            var children = (Multipart) part.getContent();
            for (int i = 0; i < children.getCount(); i++) collectTextParts(children.getBodyPart(i), result);
        } else if (part.isMimeType("text/*")) result.add(part);
    }
}
