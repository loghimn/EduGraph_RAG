package fu.coreservice.service.mail;

import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Shared email shell; content templates contain only email-safe, inline-styled HTML. */
public final class EmailTemplates {
    private EmailTemplates() {}

    public record EmailContent(String subject, String text, String html) {}

    public static EmailContent passwordReset(String otp) {
        String subject = "EduGraph-RAG - Mã xác minh đặt lại mật khẩu";
        String content = template("password-reset.html").replace("{{otp}}", HtmlUtils.htmlEscape(otp));
        // Insert user-controlled values last so they are never interpreted as template markers.
        String html = template("layout.html")
                .replace("{{title}}", subject)
                .replace("{{preheader}}", "Mã xác minh đặt lại mật khẩu có hiệu lực trong 5 phút. Không chia sẻ mã với bất kỳ ai.")
                .replace("{{eyebrow}}", "BẢO MẬT TÀI KHOẢN")
                .replace("{{heading}}", "Lấy lại quyền truy cập.<br>Tiếp tục hành trình học.")
                .replace("{{intro}}", "Đặt lại mật khẩu EduGraph-RAG của bạn chỉ trong vài bước.")
                .replace("{{content}}", content);
        String text = """
                Xin chào bạn,

                Mã xác minh đặt lại mật khẩu EduGraph-RAG của bạn là: %s
                Mã có hiệu lực trong 5 phút và chỉ được sử dụng một lần.

                1. Quay lại trang đặt lại mật khẩu trên EduGraph-RAG.
                2. Nhập mã xác minh và tạo mật khẩu mới.

                Không chia sẻ mã này với bất kỳ ai. EduGraph-RAG không bao giờ yêu cầu bạn
                cung cấp mã qua email hoặc tin nhắn.
                Nếu bạn không yêu cầu đặt lại mật khẩu, hãy bỏ qua email này.
                Mật khẩu hiện tại của bạn vẫn được giữ nguyên.

                EduGraph-RAG - Kết nối tri thức. Mở lối học tập.
                Email tự động. Vui lòng không trả lời email này.
                """.formatted(otp);
        return new EmailContent(subject, text, html);
    }

    private static String template(String name) {
        try (var input = new ClassPathResource("mail/" + name).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new MailPreparationException("Unable to load email template", error);
        }
    }
}
