package com.dskow.eventplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class RateLimitFilterTest {

    @Test
    void allowsRequestsUpToBurstThenReturns429() {
        // 1 rps, burst of 3 — first 3 should pass, 4th should be throttled
        // (refill in 1ms is negligible).
        RateLimitFilter filter = new RateLimitFilter(1, 3);
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);
        Mockito.when(chain.filter(Mockito.any())).thenReturn(Mono.empty());

        for (int i = 0; i < 3; i++) {
            MockServerWebExchange exchange = exchangeWithKey("k1");
            filter.filter(exchange, chain).block();
            assertThat(exchange.getResponse().getStatusCode())
                .as("request %d should pass", i + 1)
                .isNull();
        }

        MockServerWebExchange throttled = exchangeWithKey("k1");
        filter.filter(throttled, chain).block();
        assertThat(throttled.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void differentApiKeysHaveSeparateBuckets() {
        RateLimitFilter filter = new RateLimitFilter(1, 1);
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);
        Mockito.when(chain.filter(Mockito.any())).thenReturn(Mono.empty());

        MockServerWebExchange a = exchangeWithKey("alice");
        MockServerWebExchange b = exchangeWithKey("bob");

        filter.filter(a, chain).block();
        filter.filter(b, chain).block();

        assertThat(a.getResponse().getStatusCode()).isNull();
        assertThat(b.getResponse().getStatusCode()).isNull();
    }

    @Test
    void exemptsActuatorPaths() {
        RateLimitFilter filter = new RateLimitFilter(1, 1);
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);
        Mockito.when(chain.filter(Mockito.any())).thenReturn(Mono.empty());

        // Spam /actuator more than the bucket allows; none should be throttled.
        for (int i = 0; i < 10; i++) {
            MockServerWebExchange ex = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());
            filter.filter(ex, chain).block();
            assertThat(ex.getResponse().getStatusCode()).isNull();
        }
    }

    private MockServerWebExchange exchangeWithKey(String key) {
        return MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/events")
                .header(ApiKeyAuthFilter.API_KEY_HEADER, key)
                .build());
    }
}
