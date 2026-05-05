package com.dskow.eventplatform.gateway.security;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
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
 * Rejects 401 if {@code X-API-Key} is missing or unknown.
 *
 * Configuration:
 * <ul>
 *   <li>{@code app.security.api-keys} — comma-separated allowlist.</li>
 *   <li>{@code app.security.allow-open-mode} — if true, an empty key list
 *       lets all traffic through. Defaults to false: an empty list is a
 *       fatal startup error so that a misconfigured env var never silently
 *       disables auth.</li>
 *   <li>{@code app.security.trusted-proxy-hops} — number of trusted reverse
 *       proxies in front of the gateway. The remote IP used in audit logs
 *       and rate-limit keys is taken from {@code X-Forwarded-For} minus
 *       this many right-most entries. Defaults to 0 (no proxy trusted).</li>
 * </ul>
 *
 * Actuator and fallback paths are exempt from auth — locking ourselves out
 * of /health would defeat the container HEALTHCHECK and the circuit-breaker
 * fallback would be unreachable.
 */
@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    public static final String API_KEY_HEADER = "X-API-Key";

    private final Set<String> validKeys;
    private final boolean allowOpenMode;
    private final XForwardedRemoteAddressResolver remoteAddressResolver;

    public ApiKeyAuthFilter(
            @Value("${app.security.api-keys:}") String csv,
            @Value("${app.security.allow-open-mode:false}") boolean allowOpenMode,
            @Value("${app.security.trusted-proxy-hops:0}") int trustedProxyHops) {
        this.validKeys = Arrays.stream(csv.split(","))
            .map(String::trim)
            .filter(k -> !k.isEmpty())
            .collect(Collectors.toUnmodifiableSet());
        this.allowOpenMode = allowOpenMode;
        this.remoteAddressResolver = trustedProxyHops > 0
            ? XForwardedRemoteAddressResolver.maxTrustedIndex(trustedProxyHops)
            : null;
    }

    @PostConstruct
    void validateConfiguration() {
        if (validKeys.isEmpty() && !allowOpenMode) {
            throw new IllegalStateException(
                "ApiKeyAuthFilter: no API keys configured (app.security.api-keys is empty) "
                + "and open mode is not enabled. Set app.security.api-keys=<csv> to require "
                + "authentication, or set app.security.allow-open-mode=true to explicitly "
                + "accept anonymous traffic. The default of fail-closed is intentional so "
                + "that a misnamed env var cannot silently disable auth.");
        }
        if (validKeys.isEmpty()) {
            log.warn("ApiKeyAuthFilter: open mode enabled — gateway accepts anonymous traffic");
        } else {
            log.info("ApiKeyAuthFilter: {} API key(s) configured", validKeys.size());
        }
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isExempt(path) || validKeys.isEmpty()) {
            return chain.filter(exchange);
        }
        String supplied = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (supplied == null || !validKeys.contains(supplied)) {
            log.warn("auth-rejected method={} path={} remote={} key-prefix={}",
                exchange.getRequest().getMethod(),
                path,
                resolveClientIp(exchange),
                redact(supplied));
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        if (remoteAddressResolver != null) {
            var resolved = remoteAddressResolver.resolve(exchange);
            if (resolved != null && resolved.getAddress() != null) {
                return resolved.getAddress().getHostAddress();
            }
        }
        var remote = exchange.getRequest().getRemoteAddress();
        return remote != null ? remote.getAddress().getHostAddress() : "unknown";
    }

    /**
     * Render a short, safe prefix of a supplied key for the audit log. Strips
     * non-alphanumerics so a header containing newlines or ANSI escapes can't
     * forge log lines, and truncates so the full secret never lands in logs.
     */
    private static String redact(String key) {
        if (key == null) {
            return "<missing>";
        }
        if (key.isEmpty()) {
            return "<empty>";
        }
        String safe = key.replaceAll("[^A-Za-z0-9]", "_");
        return safe.length() <= 4 ? "<short>" : safe.substring(0, 4) + "...";
    }

    private static boolean isExempt(String path) {
        return path.startsWith("/actuator") || path.startsWith("/fallback");
    }

    @Override
    public int getOrder() {
        return -100;
    }
}
