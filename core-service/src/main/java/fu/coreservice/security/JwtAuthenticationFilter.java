package fu.coreservice.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (request.getServletPath().startsWith("/api/")
                && authHeader != null && authHeader.startsWith("Bearer ")) {
            SecurityContextHolder.clearContext();
        }
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            if (request.getServletPath().startsWith("/api/")) validateSessionAuthentication(request);
            log.debug("No Bearer token found for {}: {}", request.getMethod(), request.getRequestURI());
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        try {
            final String userEmail = jwtService.extractUsername(jwt);

            if (userEmail != null) {
                // Revalidate against current credentials on every Bearer request, including
                // requests that also carry an existing HTTP session.
                SecurityContextHolder.clearContext();
                UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);
                if (jwtService.isTokenValid(jwt, userDetails)) {
                    var authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            log.error("JWT filter ERROR on {}: {}", request.getRequestURI(), e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    /** Revalidate cookie-only requests; a stored SecurityContext must not bypass logout-all. */
    private void validateSessionAuthentication(HttpServletRequest request) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return;
        SessionUserDetails signedIn;
        if (authentication instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken oauth) {
            var session = request.getSession(false);
            Object account = session == null ? null : session.getAttribute(SessionUserDetails.OAUTH2_SESSION_ACCOUNT);
            if (!(account instanceof SessionUserDetails saved)
                    || !saved.getUsername().equals(oauth.getPrincipal().getAttribute("email"))) {
                invalidateSession(request);
                return;
            }
            signedIn = saved;
        } else if (authentication.getPrincipal() instanceof SessionUserDetails saved) {
            signedIn = saved;
        } else {
            return;
        }
        try {
            UserDetails loaded = userDetailsService.loadUserByUsername(signedIn.getUsername());
            if (!(loaded instanceof SessionUserDetails current) || !current.isEnabled()
                    || current.getSessionVersion() != signedIn.getSessionVersion()
                    || !java.util.Objects.equals(current.getPassword(), signedIn.getPassword())
                    || !new java.util.HashSet<>(current.getAuthorities()).equals(new java.util.HashSet<>(signedIn.getAuthorities()))) {
                invalidateSession(request);
            }
        } catch (Exception exception) {
            invalidateSession(request);
        }
    }

    private void invalidateSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
