package fu.coreservice.dto.auth;

import fu.coreservice.entity.ApprovalStatus;
import lombok.Builder;
import lombok.Getter;

/** Token fields are null when registration requires approval. */
@Getter
@Builder
public class RegisterResponse {
    private ApprovalStatus approvalStatus;
    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;
}
