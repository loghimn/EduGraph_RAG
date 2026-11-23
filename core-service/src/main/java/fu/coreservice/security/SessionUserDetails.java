package fu.coreservice.security;

import java.util.Collection;
import org.springframework.security.core.GrantedAuthority;

/** Carries the current account session generation loaded from the database. */
public class SessionUserDetails extends org.springframework.security.core.userdetails.User {
    private final long sessionVersion;

    public SessionUserDetails(String email, String password, boolean enabled,
                              Collection<? extends GrantedAuthority> authorities, long sessionVersion) {
        super(email, password, enabled, true, true, true, authorities);
        this.sessionVersion = sessionVersion;
    }

    public long getSessionVersion() { return sessionVersion; }
}
