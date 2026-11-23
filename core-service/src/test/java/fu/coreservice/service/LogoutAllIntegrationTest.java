package fu.coreservice.service;
import fu.coreservice.client.AiCoreClient;
import fu.coreservice.dto.auth.*;
import fu.coreservice.entity.*;
import fu.coreservice.repository.*;
import fu.coreservice.security.JwtService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
@SpringBootTest
@TestPropertySource(locations="classpath:application-context-test.properties",properties="DB_URL=jdbc:h2:mem:logout-all;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
@Transactional
class LogoutAllIntegrationTest {
 @MockitoBean AiCoreClient ai;
 @MockitoBean SupabaseStorageService storage;
 @Autowired AuthService auth;
 @Autowired UserRepository users;
 @Autowired UserSessionRepository sessions;
 @Autowired UserDetailsService details;
 @Autowired JwtService jwt;
 @Autowired PasswordEncoder encoder;
 @Autowired org.springframework.web.context.WebApplicationContext web;
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();}
 @Test void revokesEveryCurrentAccountTokenButKeepsOtherAccountsAndAllowsNewLogin(){
  User first=account(),other=account();var laptop=login(first);var phone=login(first);var unrelated=login(other);
  assertThat(laptop.getRefreshToken()).isNotEqualTo(phone.getRefreshToken());assertThat(sessions.findByUser(first)).hasSize(2);
  signIn(first);auth.logoutAllSessions();assertThat(users.findById(first.getUserId()).orElseThrow().getSessionVersion()).isEqualTo(1);
  assertThat(sessions.findByUser(first)).isEmpty();assertThat(sessions.findByUser(other)).hasSize(1);
  var revoked=details.loadUserByUsername(first.getEmail());
  assertThat(jwt.isTokenValid(laptop.getAccessToken(),revoked)).isFalse();assertThat(jwt.isTokenValid(phone.getAccessToken(),revoked)).isFalse();
  assertThat(jwt.isTokenValid(unrelated.getAccessToken(),details.loadUserByUsername(other.getEmail()))).isTrue();
  assertThatThrownBy(()->auth.refreshAccessToken(new RefreshTokenRequest(laptop.getRefreshToken()))).isInstanceOf(fu.coreservice.exception.AppException.class);
  assertThatThrownBy(()->auth.refreshAccessToken(new RefreshTokenRequest(phone.getRefreshToken()))).isInstanceOf(fu.coreservice.exception.AppException.class);
  var fresh=login(first);assertThat(jwt.isTokenValid(fresh.getAccessToken(),details.loadUserByUsername(first.getEmail()))).isTrue();
  assertThat(auth.refreshAccessToken(new RefreshTokenRequest(fresh.getRefreshToken())).getAccessToken()).isNotBlank();
  signIn(first);auth.logoutAllSessions();assertThat(jwt.isTokenValid(fresh.getAccessToken(),details.loadUserByUsername(first.getEmail()))).isFalse();
 }
 @Test void noPrincipalCannotRevokeAnyAccount(){
  User user=account();login(user);SecurityContextHolder.clearContext();
  assertThatThrownBy(auth::logoutAllSessions).isInstanceOf(fu.coreservice.exception.AppException.class);
  assertThat(sessions.findByUser(user)).hasSize(1);assertThat(user.getSessionVersion()).isZero();
 }

 @Test void revokedAccessAndRefreshAreRejectedThroughRealHttpFilters() throws Exception {
  User user=account();var laptop=login(user);var phone=login(user);
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(web)
   .addFilters(web.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me").servletPath("/api/auth/me")
   .header("Authorization","Bearer "+phone.getAccessToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/logout-all").servletPath("/api/auth/logout-all")
   .header("Authorization","Bearer "+laptop.getAccessToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/auth/me").servletPath("/api/auth/me")
   .header("Authorization","Bearer "+phone.getAccessToken())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/refresh").servletPath("/api/auth/refresh")
   .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"refreshToken\":\""+phone.getRefreshToken()+"\"}"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
 }
 private User account(){String id=UUID.randomUUID().toString();return users.saveAndFlush(User.builder().email(id+"@test.local").username(id).role(UserRole.STUDENT).passwordHash(encoder.encode("test-password")).build());}
 private AuthResponse login(User user){return auth.login(new LoginRequest(user.getEmail(),"test-password"),new MockHttpServletRequest());}
 private void signIn(User user){var principal=details.loadUserByUsername(user.getEmail());SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,principal.getAuthorities()));}
}
