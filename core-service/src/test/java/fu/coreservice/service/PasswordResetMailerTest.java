package fu.coreservice.service;

import fu.coreservice.service.impl.PasswordResetMailerImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PasswordResetMailerTest {
    @Test void sendsOtpWithoutResetLink() {
        JavaMailSender sender = mock(JavaMailSender.class);
        PasswordResetMailerImpl mailer = new PasswordResetMailerImpl(sender);
        ReflectionTestUtils.setField(mailer, "from", "sender@example.com");
        mailer.send("learner@example.com", "012345");
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("learner@example.com");
        assertThat(message.getValue().getFrom()).isEqualTo("sender@example.com");
        assertThat(message.getValue().getText()).contains("012345", "5 minutes").doesNotContain("http", "token=");
    }
}
