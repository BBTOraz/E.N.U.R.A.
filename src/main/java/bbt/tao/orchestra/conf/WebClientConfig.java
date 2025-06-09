package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.manager.init.EnuPortalApiAuthenticator;
import bbt.tao.orchestra.manager.init.PlatonusApiAuthenticator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Configuration
public class WebClientConfig {

    @Value("${tts.service.base-url}")
    private String ttsServiceBaseUrl;

    @Value("${enu.portal.base-url}")
    private String enuPortalBaseUrl;

    @Value("${platonus.api.base-url}")
    private String platonusBaseUrl;

    private final EnuPortalApiAuthenticator enuPortalAuthenticator;
    private final PlatonusApiAuthenticator platonusApiAuthenticator;

    public WebClientConfig(EnuPortalApiAuthenticator enuPortalSessionManager, PlatonusApiAuthenticator platonusApiAuthenticator) {
        this.enuPortalAuthenticator = enuPortalSessionManager;
        this.platonusApiAuthenticator = platonusApiAuthenticator;
    }

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
                .filter(enuPortalAuthenticator.authenticationFilter())
                .codecs(conf -> conf.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean
    public WebClient platonusWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(platonusBaseUrl)
                .filter(platonusApiAuthenticator.authenticationFilter())
                .codecs(conf -> conf.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }
}
