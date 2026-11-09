package fu.coreservice.repository;

import fu.coreservice.entity.Course;
import fu.coreservice.entity.User;
import fu.coreservice.entity.UserRole;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalChatCourseScopeTest {
    @Test
    void globalSearchIncludesOnlyPublicCoursesAndRechecksVisibility() {
        try (var factory = new Configuration()
                .addAnnotatedClass(User.class).addAnnotatedClass(Course.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID())
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true")
                .buildSessionFactory();
             var em = factory.createEntityManager()) {
            em.getTransaction().begin();
            User owner = User.builder().email("owner@test.local").username("owner")
                    .passwordHash("test").role(UserRole.INSTRUCTOR).build();
            em.persist(owner);
            Course publicCourse = course(owner, true, false);
            em.persist(publicCourse);
            // All four flag combinations must obey the same rule, regardless
            // of who owns the course or whether anyone enrolled in it.
            em.persist(course(owner, false, false));
            em.persist(course(owner, false, true));
            em.persist(course(owner, true, true));
            em.getTransaction().commit();

            CourseRepository repository = new JpaRepositoryFactory(em).getRepository(CourseRepository.class);
            assertEquals(List.of(publicCourse.getCourseId()), repository.findGlobalChatCourseIds());

            em.getTransaction().begin();
            publicCourse.setPublic(false);
            em.getTransaction().commit();
            assertEquals(List.of(), repository.findGlobalChatCourseIds());

            em.getTransaction().begin();
            publicCourse.setPublic(true);
            publicCourse.setNotebook(true);
            em.getTransaction().commit();
            assertEquals(List.of(), repository.findGlobalChatCourseIds());
        }
    }

    private Course course(User owner, boolean isPublic, boolean notebook) {
        return Course.builder().user(owner).courseName("Data structures").description("Test")
                .domain("CS").language("vi").isPublic(isPublic).notebook(notebook).build();
    }
}
