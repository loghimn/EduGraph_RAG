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

    public static EmailContent instructorReview(String username, boolean approved, boolean active, String reason) {
        String subject = approved ? "EduGraph-RAG - Đăng ký giảng viên đã được phê duyệt"
                : "EduGraph-RAG - Kết quả xét duyệt đăng ký giảng viên";
        String greeting = "Xin chào " + (username == null || username.isBlank() ? "bạn" : username) + ",";
        String result = approved ? "Đăng ký tài khoản giảng viên của bạn đã được quản trị viên phê duyệt."
                : "Đăng ký tài khoản giảng viên của bạn đã bị quản trị viên từ chối.";
        String nextStep = approved
                ? active ? "Bạn có thể đăng nhập EduGraph-RAG bằng tài khoản đã đăng ký và sử dụng các chức năng dành cho giảng viên."
                         : "Tài khoản của bạn hiện đang bị khóa. Vui lòng liên hệ quản trị viên để được hỗ trợ trước khi đăng nhập."
                : "Bạn chưa thể đăng nhập bằng tài khoản này. Nếu cần giải đáp về kết quả xét duyệt, vui lòng liên hệ quản trị viên.";
        String rejection = !approved && reason != null && !reason.isBlank() ? reason.strip() : null;
        String content = "<p style=\"margin:0 0 16px;font-weight:700;\">" + HtmlUtils.htmlEscape(greeting) + "</p>"
                + "<p style=\"margin:0 0 20px;\">" + result + "</p>"
                + (rejection == null ? "" : "<div style=\"padding:16px;background-color:#fff8eb;border:1px solid #f3dfb7;border-radius:12px;\">"
                    + "<p style=\"margin:0 0 8px;font-weight:700;\">Lý do từ chối</p>"
                    + "<p style=\"margin:0;white-space:pre-wrap;overflow-wrap:anywhere;\">" + HtmlUtils.htmlEscape(rejection) + "</p></div>")
                + "<p style=\"margin:20px 0 0;\">" + nextStep + "</p>";
        // Insert escaped user content last; it must not be interpreted as layout placeholders.
        String html = template("layout.html")
                .replace("{{title}}", subject)
                .replace("{{preheader}}", result)
                .replace("{{eyebrow}}", "ĐĂNG KÝ GIẢNG VIÊN")
                .replace("{{heading}}", approved ? "Đăng ký đã được phê duyệt" : "Kết quả xét duyệt đăng ký")
                .replace("{{intro}}", "Thông báo từ quản trị viên EduGraph-RAG.")
                .replace("{{content}}", content);
        String text = greeting + "\n\n" + result + "\n\n"
                + (rejection == null ? "" : "Lý do từ chối:\n" + rejection + "\n\n")
                + nextStep + "\n\nEmail tự động từ EduGraph-RAG. Vui lòng không trả lời email này.\n";
        return new EmailContent(subject, text, html);
    }

    public static EmailContent coursePrivacy(String courseName) {
        String subject = "EduGraph-RAG - Thông báo thay đổi quyền truy cập khóa học";
        String html = template("layout.html")
                .replace("{{title}}", subject)
                .replace("{{preheader}}", "Khóa học đã chuyển sang riêng tư. Lịch sử hội thoại và dữ liệu học tập của bạn vẫn được giữ nguyên.")
                .replace("{{eyebrow}}", "CẬP NHẬT KHÓA HỌC")
                .replace("{{heading}}", "Khóa học đã chuyển<br>sang riêng tư")
                .replace("{{intro}}", "Thông tin về quyền truy cập và dữ liệu học tập của bạn.")
                .replace("{{content}}", template("course-privacy.html"))
                // Course names are user content, never HTML or template instructions.
                .replace("{{courseName}}", HtmlUtils.htmlEscape(courseName));
        String text = """
                Xin chào bạn,

                Giảng viên đã chuyển khóa học "%s" sang chế độ riêng tư và gỡ quyền tham gia của bạn.

                BẠN VẪN CÓ THỂ
                Xem lịch sử hội thoại của mình trong mục AI. Dữ liệu học tập của bạn vẫn được giữ nguyên.

                QUYỀN TRUY CẬP ĐÃ TẠM DỪNG
                Bạn không thể gửi câu hỏi mới, tạo hội thoại mới hoặc truy cập tài liệu và đồ thị của khóa học.

                Để xem lại hội thoại: mở EduGraph-RAG, vào mục AI và chọn khóa học có nhãn "Chỉ xem lịch sử".
                Nếu khóa học được công khai trở lại, bạn có thể đăng ký lại để tiếp tục học và trò chuyện.

                Email tự động từ EduGraph-RAG. Vui lòng không trả lời email này.
                """.formatted(courseName);
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
