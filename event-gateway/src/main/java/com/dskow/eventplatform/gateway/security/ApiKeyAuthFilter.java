package com.dskow.eventplatform.gateway.security;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Rejects 401 if {@code X-API-Key} is missing or unknown. Configured via the
 * {@code APP_SECURITY_API_KEYS} env var as a comma-separated list. If the list
 * is empty (default for local dev), the filter is a no-op and a warning is
 * logged at startup so the open-mode is visible in operations.
 *
 * Actuator and fallback paths are exempt — locking ourselves out of /health
 * would defeat the container HEALTHCHECK and the circuit-breaker fallback.
 */
@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    public static final String API_KEY_HEADER = "X-API-Key";

    private final Set<String> validKeys;

    public ApiKeyAuthFilter(@Value("${app.security.api-keys:}") String csv) {
        this.validKeys = Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(k -> !k.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        if (validKeys.isEmpty()) {
            log.warn("ApiKeyAuthFilter: no API keys configured (app.security.api-keys empty); "
                + "gateway is OPEN — set the env var before deploying anywhere reachable");
        } else {
            log.info("ApiKeyAuthFilter: {} API key(s) configured", validKeys.size());
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isExempt(path) || validKeys.isEmpty()) {
            return chain.filter(exchange);
        }
        String supplied = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (supplied == null || !validKeys.contains(supplied)) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private static boolean isExempt(String path) {
        return path.startsWith("/actuator") || path.startsWith("/fallback");
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
