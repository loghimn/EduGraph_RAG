package fu.coreservice.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    /**
     * Base64-encoded secret decoding to >= 256 bits (same value as gateway tests).
     */
    private static final String SECRET =
            "c2VjcmV0LWtleS1mb3ItZWR1Z3JhcGgtcmFnLWFwcGxpY2F0aW9uLTIwMjQtdXNlLWZvci1qd3QtdG9rZW4tc2lnbmluZw==";

    private static final String EMAIL = "user@example.com";

    private JwtService jwtService;
    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", SECRET);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 15 * 60 * 1000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 7 * 24 * 60 * 60 * 1000L);

        userDetails = User.withUsername(EMAIL)
                .password("password")
                .roles("USER")
                .build();
    }

    @Test
    void generateAccessToken_carriesAccessTokenTypeClaim() {
        String token = jwtService.generateAccessToken(userDetails, 1L, "USER");

        String tokenType = jwtService.extractClaim(token, claims -> claims.get("tokenType", String.class));
        assertThat(tokenType).isEqualTo(JwtService.TOKEN_TYPE_ACCESS);
        assertThat(jwtService.isAccessToken(token)).isTrue();
        assertThat(jwtService.isTokenValid(token, userDetails)).isTrue();
    }

    @Test
    void generateRefreshToken_carriesRefreshTokenTypeClaim() {
        String token = jwtService.generateRefreshToken(userDetails, 1L, "USER");

        String tokenType = jwtService.extractClaim(token, claims -> claims.get("tokenType", String.class));
        assertThat(tokenType).isEqualTo(JwtService.TOKEN_TYPE_REFRESH);
        assertThat(jwtService.isAccessToken(token)).isFalse();
    }

    @Test
    void refreshToken_isRejectedByIsTokenValid() {
        // Unexpired, correctly-signed refresh token must NOT authenticate to protected routes.
        String refreshToken = jwtService.generateRefreshToken(userDetails, 1L, "USER");

        assertThat(jwtService.isTokenValid(refreshToken, userDetails)).isFalse();
    }

    @Test
    void legacyTokenWithoutTypeClaim_isRejectedByIsTokenValid() {
        // Tokens issued before the tokenType claim existed must fail closed.
        String legacyToken = buildTokenWithoutTypeClaim();

        assertThat(jwtService.isAccessToken(legacyToken)).isFalse();
        assertThat(jwtService.isTokenValid(legacyToken, userDetails)).isFalse();
    }

    @Test
    void expiredAccessToken_isRejectedByIsTokenValid() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1000L);

        String expiredToken = jwtService.generateAccessToken(userDetails, 1L, "USER");

        assertThat(jwtService.isTokenValid(expiredToken, userDetails)).isFalse();
    }

    @Test
    void accessTokenOfDifferentUser_isRejectedByIsTokenValid() {
        String token = jwtService.generateAccessToken(userDetails, 1L, "USER");
        UserDetails otherUser = User.withUsername("other@example.com")
                .password("password")
                .roles("USER")
                .build();

        assertThat(jwtService.isTokenValid(token, otherUser)).isFalse();
    }

    private String buildTokenWithoutTypeClaim() {
        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("userId", 1L);
        extraClaims.put("role", "USER");
        return Jwts.builder()
                .claims(extraClaims)
                .subject(EMAIL)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + 60 * 60 * 1000L))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET)))
                .compact();
    }
}
