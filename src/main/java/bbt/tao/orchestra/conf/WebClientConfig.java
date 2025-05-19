package bbt.tao.orchestra.conf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Configuration
public class WebClientConfig {

    @Value("${tts.service.base-url}")
    private String ttsServiceBaseUrl;

    @Value("${enu.portal.base-url}")
    private String enuPortalBaseUrl;

    @Bean
    public WebClient ttsWebClient(WebClient.Builder webClientBuilder) {
        return webClientBuilder
                .baseUrl(ttsServiceBaseUrl)
                .build();
    }

    @Bean
    public WebClient enuPortalWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(enuPortalBaseUrl)
                .codecs(conf -> conf.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .filter(logRequest())
                .filter(logResponse())
                .build();
    }

    private ExchangeFilterFunction logRequest() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            log.debug("Request: {} {}", clientRequest.method(), clientRequest.url());
            clientRequest.headers().forEach((name, values) ->
                    values.forEach(value -> log.debug("{}={}", name, value)));
            return Mono.just(clientRequest);
        });
    }

    private ExchangeFilterFunction logResponse() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            log.debug("Response status: {}", clientResponse.statusCode());
            clientResponse.headers().asHttpHeaders().forEach((name, values) ->
                    values.forEach(value -> log.debug("{}={}", name, value)));
            return Mono.just(clientResponse);
        });
    }
}
