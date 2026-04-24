package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.manager.init.EnuPortalApiAuthenticator;
import bbt.tao.orchestra.manager.init.PlatonusApiAuthenticator;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import javax.net.ssl.SSLException;

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
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(insecureHttpClient()))
                .baseUrl(enuPortalBaseUrl)
                .filter(enuPortalAuthenticator.authenticationFilter())
                .codecs(conf -> conf.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @Bean
    public WebClient platonusWebClient(WebClient.Builder builder) {
        return builder.clone()
                .clientConnector(new ReactorClientHttpConnector(insecureHttpClient()))
                .baseUrl(platonusBaseUrl)
                .filter(platonusApiAuthenticator.authenticationFilter())
                .codecs(conf -> conf.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    // SSL verification disabled for internal ENU/Platonus hosts with self-signed certs
    private HttpClient insecureHttpClient() {
        try {
            SslContext sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            return HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                    .secure(spec -> spec.sslContext(sslContext))
                    .doOnConnected(conn -> conn
                            .addHandlerLast(new ReadTimeoutHandler(120))
                            .addHandlerLast(new WriteTimeoutHandler(120)));
        } catch (SSLException e) {
            log.warn("Failed to build insecure SSL context, falling back to default", e);
            return HttpClient.create();
        }
    }
}
