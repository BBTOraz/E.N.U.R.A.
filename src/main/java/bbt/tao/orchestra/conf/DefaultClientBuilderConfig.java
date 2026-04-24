package bbt.tao.orchestra.conf;

import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import javax.net.ssl.SSLException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Configuration
@Slf4j
public class DefaultClientBuilderConfig {
    private static final String REDACTED = "<redacted>";
    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key"
    );

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);

        var bufferingFactory = new BufferingClientHttpRequestFactory(factory);

        return RestClient.builder()
                .requestFactory(bufferingFactory)
                .requestInterceptor((request, body, execution) -> {
                    log.info("→ {} {} headers={}",
                            request.getMethod(), request.getURI(), redactHeaders(request.getHeaders()));

                    ClientHttpResponse response = execution.execute(request, body);

                    log.info("← {} {} headers={}",
                            response.getStatusCode(), response.getStatusText(), redactHeaders(response.getHeaders()));

                    return response;
                });
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient tcp = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .wiretap("reactor.netty.http.client.HttpClient",
                        LogLevel.DEBUG, AdvancedByteBufFormat.SIMPLE)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(120))
                        .addHandlerLast(new WriteTimeoutHandler(120))
                )
                .secure(spec -> {
                    try {
                        SslContext sslCtx = SslContextBuilder.forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .build();
                        spec.sslContext(sslCtx);
                    } catch (SSLException e) {
                        log.warn("Could not build insecure SSL context", e);
                    }
                });

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(tcp))
                .filter(logRequest())
                .filter(logResponse());
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            log.info("Request: {} {} headers={}",
                    clientRequest.method(), clientRequest.url(), redactHeaders(clientRequest.headers()));
            return Mono.just(clientRequest);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            log.info("Response status: {} headers={}",
                    clientResponse.statusCode(), redactHeaders(clientResponse.headers().asHttpHeaders()));
            return Mono.just(clientResponse);
        });
    }

    private Map<String, List<String>> redactHeaders(HttpHeaders headers) {
        HttpHeaders sanitized = new HttpHeaders();
        headers.forEach((name, values) -> {
            if (SENSITIVE_HEADERS.contains(name.toLowerCase())) {
                sanitized.put(name, List.of(REDACTED));
            } else {
                sanitized.put(name, values);
            }
        });
        return sanitized;
    }
}
