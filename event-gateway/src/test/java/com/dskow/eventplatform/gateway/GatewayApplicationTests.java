package com.dskow.eventplatform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    // The default fail-closed posture would block context load with no keys
    // configured. Test-only override to take the open path; auth-specific
    // behaviour is covered in ApiKeyAuthFilterTest.
    "app.security.allow-open-mode=true"
})
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // verifies Spring Cloud Gateway routes, Resilience4j circuit breaker,
        // and fallback controller wire up cleanly at startup
    }
}
