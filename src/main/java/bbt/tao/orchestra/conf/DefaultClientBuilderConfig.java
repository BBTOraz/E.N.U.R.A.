package bbt.tao.orchestra.conf;

import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

import java.nio.charset.StandardCharsets;

@Configuration
@Slf4j
public class DefaultClientBuilderConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);

        var bufferingFactory = new BufferingClientHttpRequestFactory(factory);

        return RestClient.builder()
                .requestFactory(bufferingFactory)
                .requestInterceptor((request, body, execution) -> {

                    log.info("→ {} {}\nHeaders: {}\nBody: {}",
                            request.getMethod(), request.getURI(),
                            request.getHeaders(),
                            new String(body, StandardCharsets.UTF_8));

                    ClientHttpResponse response = execution.execute(request, body);

                    String responseBody = StreamUtils.copyToString(
                            response.getBody(), StandardCharsets.UTF_8);
                    log.info("← {} {}\nHeaders: {}\nBody: {}",
                            response.getStatusCode(), response.getStatusText(),
                            response.getHeaders(),
                            responseBody);

                    return response;
                });
    }

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient tcp = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .wiretap("reactor.netty.http.client.HttpClient",
                        LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(120))
                        .addHandlerLast(new WriteTimeoutHandler(120))
                );

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(tcp))
                .filter(logRequest())
                .filter(logResponse());
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
           /* log.info("Request: {} {}", clientRequest.method(), clientRequest.url());*/
            clientRequest.headers().forEach((name, values) ->
                    values.forEach(value -> log.info("{}={}", name, value)));
            return Mono.just(clientRequest);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
           /* log.info("Response status: {}", clientResponse.statusCode());*/
            clientResponse.headers().asHttpHeaders().forEach((name, values) ->
                    values.forEach(value -> log.info("{}={}", name, value)));
            return Mono.just(clientResponse);
        });
    }
}
