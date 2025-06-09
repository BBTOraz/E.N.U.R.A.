package bbt.tao.orchestra.manager;

import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

public interface ApiAuthenticator {
    Mono<Boolean> ensureAuthenticated();

    ExchangeFilterFunction authenticationFilter();

    void invalidateSession();

    String getCookieValue();
}
