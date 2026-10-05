package fu.gatewayservice.filter;

import fu.gatewayservice.dto.ErrorResponse;
import fu.gatewayservice.security.JwtService;
import fu.gatewayservice.security.PublicPaths;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * WebFlux GlobalFilter that enforces JWT auth on non-public paths.
 * CORS preflight (OPTIONS) is always allowed; gateway globalcors handles the response.
 */
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final PublicPaths publicPaths;

    public JwtAuthenticationGlobalFilter(JwtService jwtService, PublicPaths publicPaths) {
        this.jwtService = jwtService;
        this.publicPaths = publicPaths;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // CORS preflight and public endpoints
        if (HttpMethod.OPTIONS.matches(request.getMethod().name()) || publicPaths.matches(path)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return writeUnauthorized(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty() || !jwtService.isValid(token)) {
            return writeUnauthorized(exchange, "Invalid or expired token");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = ErrorResponse.errorJson("AUTH_001", message);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // Run before routing filters
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
