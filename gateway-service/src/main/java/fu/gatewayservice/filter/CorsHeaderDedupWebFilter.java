package fu.gatewayservice.filter;

import org.reactivestreams.Publisher;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Deduplicate CORS headers trên response của gateway.
 * Khi cả gateway globalcors lẫn core-service CorsFilter đều thêm
 * Access-Control-Allow-*, browser từ chối response (nhiều giá trị
 * cho cùng 1 header). Filter này giữ lại giá trị đầu tiên.
 *
 * <p>Deduplicate được áp dụng trong {@code writeWith}, {@code writeAndFlushWith}
 * và {@code setComplete} để bao phủ mọi đường response được ghi.</p>
 */
@Component
public class CorsHeaderDedupWebFilter implements WebFilter, Ordered {

    private static final String[] CORS_HEADERS = {
            "Access-Control-Allow-Origin",
            "Access-Control-Allow-Credentials",
            "Access-Control-Allow-Headers",
            "Access-Control-Allow-Methods",
            "Access-Control-Expose-Headers"
    };

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(response) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                deduplicate(getHeaders());
                return super.writeWith(body);
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                deduplicate(getHeaders());
                return super.writeAndFlushWith(body);
            }

            @Override
            public Mono<Void> setComplete() {
                deduplicate(getHeaders());
                return super.setComplete();
            }
        };
        return chain.filter(exchange.mutate().response(decorator).build());
    }

    private void deduplicate(HttpHeaders headers) {
        for (String name : CORS_HEADERS) {
            List<String> values = headers.get(name);
            if (values != null && values.size() > 1 && values.get(0) != null) {
                headers.set(name, values.get(0));
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}