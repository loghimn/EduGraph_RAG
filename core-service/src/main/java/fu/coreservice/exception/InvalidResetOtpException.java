package fu.coreservice.exception;

/** Signals a rejected OTP while allowing failed-attempt updates to commit. */
public class InvalidResetOtpException extends AppException {
    public InvalidResetOtpException() {
        super(ErrorCode.INVALID_RESET_OTP);
    }
}
