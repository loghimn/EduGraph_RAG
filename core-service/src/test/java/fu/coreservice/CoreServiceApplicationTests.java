package fu.coreservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource("classpath:application-context-test.properties")
class CoreServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
