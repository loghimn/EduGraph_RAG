package fu.gatewayservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "jwt.secret=c2VjcmV0LWtleS1mb3ItZWR1Z3JhcGgtcmFnLWFwcGxpY2F0aW9uLTIwMjQtdXNlLWZvci1qd3QtdG9rZW4tc2lnbmluZw==",
        "spring.cloud.gateway.server.webflux.enabled=true"
})
class GatewayServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
