package com.dskow.eventplatform.gateway.security;

import java.util.concurrent.ConcurrentHashMap;
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
 * In-memory token-bucket rate limit, keyed by API key (or remote IP if absent).
 * Returns 429 once the bucket empties; refills at the configured rate.
 *
 * Single-instance only — replicas would each have their own bucket. For
 * multi-instance deployment, swap in Spring Cloud Gateway's Redis rate limiter.
 * That's documented as a follow-up in MISSION_CRITICAL_READINESS.md.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final int rps;
    private final int burst;
    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(
            @Value("${app.security.rate-limit-rps:10}") int rps,
            @Value("${app.security.rate-limit-burst:20}") int burst) {
        this.rps = rps;
        this.burst = burst;
        log.info("RateLimitFilter: {} req/s, burst {}", rps, burst);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/actuator") || path.startsWith("/fallback")) {
            return chain.filter(exchange);
        }
        String key = resolveKey(exchange);
        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(rps, burst));
        if (!bucket.tryConsume()) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private static String resolveKey(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(ApiKeyAuthFilter.API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null ? "ip:" + remote.getAddress().getHostAddress() : "ip:unknown";
    }

    @Override
    public int getOrder() {
        // After auth (-100), before routing.
        return -50;
    }

    static final class TokenBucket {
        private final double rate;
        private final double capacity;
        private double tokens;
        private long lastRefillNs;

        TokenBucket(double rate, double capacity) {
            this.rate = rate;
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefillNs = System.nanoTime();
        }

        synchronized boolean tryConsume() {
            long now = System.nanoTime();
            double elapsedSec = (now - lastRefillNs) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSec * rate);
            lastRefillNs = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
