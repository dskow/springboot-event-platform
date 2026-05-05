package com.dskow.eventplatform.gateway.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * In-memory token-bucket rate limit, keyed by API key (or remote IP if absent).
 * Returns 429 once the bucket empties; refills at the configured rate.
 *
 * The bucket map is a Caffeine cache with a hard size cap and an idle
 * expiry — without these an attacker could rotate {@code X-API-Key} values
 * (or spoof source IPs behind a proxy) to insert millions of entries and
 * exhaust heap. Eviction is silent: if a long-idle key reappears it simply
 * gets a fresh full bucket, which is the same as never having seen it.
 *
 * Single-instance only — replicas would each have their own bucket. For
 * multi-instance deployment, swap in Spring Cloud Gateway's Redis rate limiter.
 */
@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final int rps;
    private final int burst;
    private final Cache<String, TokenBucket> buckets;
    private final XForwardedRemoteAddressResolver remoteAddressResolver;

    public RateLimitFilter(
            @Value("${app.security.rate-limit-rps:10}") int rps,
            @Value("${app.security.rate-limit-burst:20}") int burst,
            @Value("${app.security.rate-limit-max-keys:100000}") long maxKeys,
            @Value("${app.security.rate-limit-idle-minutes:10}") long idleMinutes,
            @Value("${app.security.trusted-proxy-hops:0}") int trustedProxyHops) {
        this.rps = rps;
        this.burst = burst;
        this.buckets = Caffeine.newBuilder()
            .maximumSize(maxKeys)
            .expireAfterAccess(Duration.ofMinutes(idleMinutes))
            .build();
        this.remoteAddressResolver = trustedProxyHops > 0
            ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops)
            : null;
        log.info("RateLimitFilter: {} req/s burst {}, cache cap {} keys, idle {}min",
            rps, burst, maxKeys, idleMinutes);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith("/actuator") || path.startsWith("/fallback")) {
            return chain.filter(exchange);
        }
        String key = resolveKey(exchange);
        TokenBucket bucket = buckets.get(key, k -> new TokenBucket(rps, burst));
        if (bucket == null || !bucket.tryConsume()) {
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private String resolveKey(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(ApiKeyAuthFilter.API_KEY_HEADER);
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        if (remoteAddressResolver != null) {
            var resolved = remoteAddressResolver.resolve(exchange);
            if (resolved != null && resolved.getAddress() != null) {
                return "ip:" + resolved.getAddress().getHostAddress();
            }
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
