package fu.gatewayservice.exception;

import fu.gatewayservice.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.util.concurrent.TimeoutException;

/**
 * JSON error responses for the gateway (aligned with core-service ApiResponse shape).
 * Never leaks stack traces or secrets to the client.
 * Uses Spring Framework {@link WebExceptionHandler} (Boot 4 removed ErrorWebExceptionHandler
 * from org.springframework.boot.web.reactive.error).
 */
@Component
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = resolveStatus(ex);
        String message = resolveMessage(status);

        log.error("Gateway error on {} {}: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath().value(),
                ex.toString());

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = ErrorResponse.errorJson(message);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    private HttpStatus resolveStatus(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            HttpStatus resolved = HttpStatus.resolve(rse.getStatusCode().value());
            return resolved != null ? resolved : HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (ex instanceof ConnectException || ex.getCause() instanceof ConnectException) {
            return HttpStatus.BAD_GATEWAY;
        }
        if (ex instanceof TimeoutException || ex.getCause() instanceof TimeoutException) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        String root = rootMessage(ex);
        if (root != null && (root.contains("Connection refused")
                || root.contains("Connection reset")
                || root.contains("No such host"))) {
            return HttpStatus.BAD_GATEWAY;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String resolveMessage(HttpStatus status) {
        return switch (status) {
            case BAD_GATEWAY -> "Upstream service is unavailable";
            case GATEWAY_TIMEOUT -> "Request timed out";
            case UNAUTHORIZED -> "Unauthorized";
            case FORBIDDEN -> "Forbidden";
            case NOT_FOUND -> "Resource not found";
            case BAD_REQUEST -> "Bad request";
            default -> "An unexpected error occurred";
        };
    }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return null;
    }

    @Override
    public int getOrder() {
        // Run before Spring Boot default error handler (order -1)
        return -10;
    }
}
