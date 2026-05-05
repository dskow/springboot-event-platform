package com.dskow.eventplatform.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // verifies Spring Cloud Gateway routes, Resilience4j circuit breaker,
        // and fallback controller wire up cleanly at startup
    }
}
