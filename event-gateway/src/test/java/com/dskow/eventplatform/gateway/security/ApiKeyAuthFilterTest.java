package com.dskow.eventplatform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class ApiKeyAuthFilterTest {

    @Test
    void rejects401WhenKeyMissing() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("good-key");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/events").build());
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void rejects401WhenKeyWrong() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("good-key");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/events")
                .header(ApiKeyAuthFilter.API_KEY_HEADER, "bad-key")
                .build());
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);

        filter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Mockito.verifyNoInteractions(chain);
    }

    @Test
    void allowsRequestWhenKeyValid() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("good-key, another-key");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/events")
                .header(ApiKeyAuthFilter.API_KEY_HEADER, "another-key")
                .build());
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);
        Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        Mockito.verify(chain).filter(exchange);
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void exemptsActuatorPaths() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("good-key");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/actuator/health").build());
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);
        Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        Mockito.verify(chain).filter(exchange);
    }

    @Test
    void openModeWhenNoKeysConfigured() {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("");
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/events").build());
        GatewayFilterChain chain = Mockito.mock(GatewayFilterChain.class);
        Mockito.when(chain.filter(exchange)).thenReturn(Mono.empty());

        filter.filter(exchange, chain).block();

        Mockito.verify(chain).filter(exchange);
    }
}
