package fu.coreservice.dto.auth;

import fu.coreservice.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserInfoResponse {

    private Long userId;
    private String email;
    private String username;
    private UserRole role;
    private Boolean isActive;
}
