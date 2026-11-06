package fu.coreservice.service;
import fu.coreservice.dto.notebook.NotebookRequest;
import fu.coreservice.entity.*;
import fu.coreservice.exception.AppException;
import fu.coreservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class NotebookServiceTest {
 @Mock CourseRepository courses;
 @Mock UserRepository users;
 @InjectMocks NotebookService service;
 User user;
 @BeforeEach void setup() {
  user = new User(); user.setUserId(1L);
  SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("owner@test.com", null, java.util.List.of()));
  when(users.findByEmail("owner@test.com")).thenReturn(Optional.of(user));
 }
 @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }
 @Test void createsPrivateNotebook() {
  when(courses.save(any(Course.class))).thenAnswer(i -> i.getArgument(0));
  var response = service.create(new NotebookRequest(" Test ", null, " Math ", "vi"));
  var captor = ArgumentCaptor.forClass(Course.class);
  verify(courses).save(captor.capture());
  assertTrue(captor.getValue().isNotebook());
  assertFalse(captor.getValue().isPublic());
  assertSame(user, captor.getValue().getUser());
  assertEquals("Test", response.title());
 }
 @Test void deniesAnotherUsersNotebook() {
  when(courses.findByCourseIdAndUser(2L, user)).thenReturn(Optional.empty());
  assertThrows(AppException.class, () -> service.get(2L));
  assertFalse(service.owns(2L));
 }
 @Test void ordinaryCourseIsNotNotebook() {
  when(courses.findByCourseIdAndUser(2L, user)).thenReturn(Optional.of(Course.builder().notebook(false).build()));
  assertThrows(AppException.class, () -> service.get(2L));
  assertFalse(service.owns(2L));
 }
}
