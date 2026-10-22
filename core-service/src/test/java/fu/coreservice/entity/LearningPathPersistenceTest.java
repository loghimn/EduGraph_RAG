package fu.coreservice.entity;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.exception.ConstraintViolationException;
import fu.coreservice.repository.LearningPathPlanRepository;
import fu.coreservice.repository.LearningPathGoalRepository;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LearningPathPersistenceTest {
    private static SessionFactory factory;

    @BeforeAll
    static void createSchema() {
        factory = new Configuration()
                .addAnnotatedClass(User.class)
                .addAnnotatedClass(Course.class)
                .addAnnotatedClass(Document.class)
                .addAnnotatedClass(LearningPathPlan.class)
                .addAnnotatedClass(LearningPathGoal.class)
                .addAnnotatedClass(LearningPathStep.class)
                .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                .setProperty("hibernate.connection.url", "jdbc:h2:mem:learning_paths;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
                .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                .setProperty("hibernate.hbm2ddl.halt_on_error", "true")
                .buildSessionFactory();
    }

    @AfterAll
    static void closeSchema() {
        if (factory != null) factory.close();
    }

    @Test
    void savesMultipleGoalsAndOrderedStepsAndCascadesDeletion() {
        Long planId = createPlan(LearningPathMode.GOAL, true);

        factory.inTransaction(session -> {
            LearningPathPlan plan = session.find(LearningPathPlan.class, planId);
            assertEquals(2, plan.getGoals().size());
            assertEquals(2, plan.getSteps().size());
            assertEquals(1, plan.getSteps().getFirst().getStepOrder());
            assertEquals(0, plan.getSteps().getFirst().getStageLevel());
            assertNull(plan.getSteps().getFirst().getOutsideDocument());
            assertEquals(LearningPathStatus.ACTIVE, plan.getStatus());
            assertEquals(3L, plan.getGraphVersion());
            assertNotNull(plan.getGeneratedAt());
            JpaRepositoryFactory repositories = new JpaRepositoryFactory(session);
            assertEquals(1, repositories.getRepository(LearningPathPlanRepository.class)
                    .findByLearnerAndCourseAndStatus(plan.getLearner(), plan.getCourse(),
                            LearningPathStatus.ACTIVE).size());
            assertEquals(2, repositories.getRepository(LearningPathGoalRepository.class)
                    .findByPlan_PlanIdOrderByGoalIdAsc(planId).size());
            session.remove(plan);
        });

        factory.inTransaction(session -> {
            assertEquals(0L, session.createSelectionQuery(
                    "select count(g) from LearningPathGoal g where g.plan.planId = :id", Long.class)
                    .setParameter("id", planId).getSingleResult());
            assertEquals(0L, session.createSelectionQuery(
                    "select count(s) from LearningPathStep s where s.plan.planId = :id", Long.class)
                    .setParameter("id", planId).getSingleResult());
        });
    }

    @Test
    void rejectsDuplicateGoalsInSamePlan() {
        Long planId = createPlan(LearningPathMode.GOAL, true);
        assertThrows(ConstraintViolationException.class, () -> factory.inTransaction(session -> {
            session.persist(LearningPathGoal.builder()
                    .plan(session.getReference(LearningPathPlan.class, planId))
                    .neo4jConceptId("C5").build());
            session.flush();
        }));
    }

    @Test
    void rejectsDuplicateStepOrderInSamePlan() {
        Long planId = createPlan(LearningPathMode.COURSE, false);
        assertThrows(ConstraintViolationException.class, () -> factory.inTransaction(session -> {
            session.persist(LearningPathStep.builder()
                    .plan(session.getReference(LearningPathPlan.class, planId))
                    .neo4jConceptId("C9").conceptLabel("Other concept").stepOrder(1).build());
            session.flush();
        }));
    }

    @Test
    void deletingSourceDocumentPreservesPlan() {
        Long planId = createPlan(LearningPathMode.DOCUMENT, false);
        factory.inTransaction(session -> {
            LearningPathPlan plan = session.find(LearningPathPlan.class, planId);
            Document document = Document.builder().course(plan.getCourse())
                    .docStatus(UploadStatus.values()[0]).build();
            session.persist(document);
            plan.setDocument(document);
        });
        factory.inTransaction(session -> {
            LearningPathPlan plan = session.find(LearningPathPlan.class, planId);
            session.remove(plan.getDocument());
        });
        factory.inTransaction(session -> {
            LearningPathPlan plan = session.find(LearningPathPlan.class, planId);
            assertNotNull(plan);
            assertNull(plan.getDocument());
        });
    }

    private Long createPlan(LearningPathMode mode, boolean addGoals) {
        return factory.fromTransaction(session -> {
            String suffix = java.util.UUID.randomUUID().toString();
            User user = User.builder().email(suffix + "@test.local").username(suffix)
                    .passwordHash("test").role(UserRole.values()[0]).build();
            session.persist(user);
            Course course = Course.builder().user(user).courseName("Math")
                    .description("Math course").domain("Math").language("en").build();
            session.persist(course);
            LearningPathPlan plan = LearningPathPlan.builder().learner(user).course(course)
                    .mode(mode).graphVersion(3L).planName("Test plan").build();
            if (addGoals) {
                for (String id : new String[]{"C4", "C5"}) {
                    plan.getGoals().add(LearningPathGoal.builder().plan(plan).neo4jConceptId(id).build());
                }
            }
            // Insert out of order to verify the mapped collection ordering on reload.
            for (int order : new int[]{2, 1}) {
                plan.getSteps().add(LearningPathStep.builder().plan(plan)
                        .neo4jConceptId("C" + order).conceptLabel("Concept " + order)
                        .stepOrder(order).stageLevel(order - 1).build());
            }
            session.persist(plan);
            return plan.getPlanId();
        });
    }
}
