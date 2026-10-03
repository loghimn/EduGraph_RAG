package fu.gatewayservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

/**
 * Validates HS256 JWTs issued by core-service.
 * Uses the same secret encoding (Base64) as core-service JwtService.
 */
@Component
public class JwtService {

    /**
     * Claim that distinguishes access tokens from refresh tokens.
     * Must match core-service JwtService.TOKEN_TYPE_CLAIM.
     */
    public static final String TOKEN_TYPE_CLAIM = "tokenType";
    public static final String TOKEN_TYPE_ACCESS = "access";

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey secretKey;

    public JwtService(@Value("${jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "JWT secret is missing. Set JWT_SECRET in gateway-service/env "
                            + "or as an environment variable (must match core-service).");
        }
        try {
            byte[] keyBytes = Decoders.BASE64.decode(secret.trim());
            this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "JWT secret is invalid. It must be Base64-encoded and decode to >= 256 bits for HS256. "
                            + "Current length after trim: " + secret.trim().length(), ex);
        }
        log.info("JwtService initialized with HS256 key ({} bytes after Base64 decode)",
                secretKey.getEncoded().length);
    }

    /**
     * @return true if the token is a well-formed, unexpired HS256 JWT
     * carrying {@code tokenType=access}. Refresh tokens are rejected.
     */
    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Claims claims = parseClaims(token);
            return isAccessToken(claims);
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean isAccessToken(Claims claims) {
        return TOKEN_TYPE_ACCESS.equals(claims.get(TOKEN_TYPE_CLAIM, String.class));
    }

    /**
     * @return claims if valid, otherwise null
     */
    public Claims getClaims(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return parseClaims(token);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public String extractUsername(String token) {
        Claims claims = getClaims(token);
        return claims != null ? claims.getSubject() : null;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
