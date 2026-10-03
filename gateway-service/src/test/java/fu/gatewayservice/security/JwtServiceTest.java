package fu.gatewayservice.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    /**
     * Base64-encoded secret decoding to >= 256 bits (same value as core-service tests).
     */
    private static final String SECRET =
            "c2VjcmV0LWtleS1mb3ItZWR1Z3JhcGgtcmFnLWFwcGxpY2F0aW9uLTIwMjQtdXNlLWZvci1qd3QtdG9rZW4tc2lnbmluZw==";

    private JwtService jwtService;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET);
        signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    }

    @Test
    void isValid_acceptsAccessTokenWithAccessTypeClaim() {
        String accessToken = buildToken(JwtService.TOKEN_TYPE_ACCESS);

        assertThat(jwtService.isValid(accessToken)).isTrue();
    }

    @Test
    void isValid_rejectsRefreshTokenEvenWhenUnexpiredAndCorrectlySigned() {
        // The vulnerability: a refresh token with the same key/claims but a valid
        // signature and unexpired exp must not authenticate to protected routes.
        String refreshToken = buildToken("refresh");

        assertThat(jwtService.isValid(refreshToken)).isFalse();
    }

    @Test
    void isValid_rejectsLegacyTokenWithoutTypeClaim() {
        String legacyToken = buildToken(null);

        assertThat(jwtService.isValid(legacyToken)).isFalse();
    }

    @Test
    void isValid_rejectsUnknownTokenType() {
        String token = buildToken("id_token");

        assertThat(jwtService.isValid(token)).isFalse();
    }

    @Test
    void isValid_rejectsExpiredAccessToken() {
        String expiredToken = Jwts.builder()
                .claims(tokenClaims(JwtService.TOKEN_TYPE_ACCESS))
                .subject("user@example.com")
                .issuedAt(new Date(System.currentTimeMillis() - 2 * 60 * 60 * 1000L))
                .expiration(new Date(System.currentTimeMillis() - 60 * 60 * 1000L))
                .signWith(signingKey)
                .compact();

        assertThat(jwtService.isValid(expiredToken)).isFalse();
    }

    @Test
    void isValid_rejectsNullOrBlankAndTamperedTokens() {
        assertThat(jwtService.isValid(null)).isFalse();
        assertThat(jwtService.isValid("")).isFalse();
        assertThat(jwtService.isValid("not-a-jwt")).isFalse();

        String valid = buildToken(JwtService.TOKEN_TYPE_ACCESS);
        int sigStart = valid.lastIndexOf('.') + 1;
        char flipped = valid.charAt(sigStart) == 'a' ? 'b' : 'a';
        String tampered = valid.substring(0, sigStart) + flipped + valid.substring(sigStart + 1);
        assertThat(jwtService.isValid(tampered)).isFalse();
    }

    @Test
    void getClaims_returnsClaimsForSignedTokens() {
        String accessToken = buildToken(JwtService.TOKEN_TYPE_ACCESS);

        Claims claims = jwtService.getClaims(accessToken);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("user@example.com");
        assertThat(claims.get(JwtService.TOKEN_TYPE_CLAIM, String.class))
                .isEqualTo(JwtService.TOKEN_TYPE_ACCESS);
    }

    private String buildToken(String tokenType) {
        return Jwts.builder()
                .claims(tokenClaims(tokenType))
                .subject("user@example.com")
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 60 * 60 * 1000L))
                .signWith(signingKey)
                .compact();
    }

    private Map<String, Object> tokenClaims(String tokenType) {
        Map<String, Object> claims = new HashMap<>();
        if (tokenType != null) {
            claims.put(JwtService.TOKEN_TYPE_CLAIM, tokenType);
        }
        claims.put("userId", 1L);
        claims.put("role", "USER");
        return claims;
    }
}
