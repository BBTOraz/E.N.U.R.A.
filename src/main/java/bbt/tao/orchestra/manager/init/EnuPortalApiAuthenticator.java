package bbt.tao.orchestra.manager.init;

import bbt.tao.orchestra.dto.enu.portal.EnuPortalLoginRequest;
import bbt.tao.orchestra.exception.UnauthorizedException;
import bbt.tao.orchestra.manager.ApiAuthenticator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
public class EnuPortalApiAuthenticator implements ApiAuthenticator {

    private final WebClient loginWebClient;
    private final String username;
    private final String password;

    private final AtomicReference<String> enuCookie = new AtomicReference<>(null);
    private final AtomicBoolean loginInProgress = new AtomicBoolean(false);
    private final Sinks.Many<Boolean> loginNotifier = Sinks.many().replay().latest();

    public EnuPortalApiAuthenticator(
            WebClient.Builder webClientBuilder,
            @Value("${enu.portal.base-url}") String enuPortalBaseUrl,
            @Value("${enu.api.username}") String username,
            @Value("${enu.api.password}") String password) {

        this.loginWebClient = webClientBuilder
                .baseUrl(enuPortalBaseUrl)
                .build();

        this.username = username;
        this.password = password;
    }

    @PostConstruct
    private void initialLogin() {
        ensureAuthenticated()
                .subscribe(success -> {
                    if (Boolean.TRUE.equals(success)) {
                        log.info("Initial ENU login succeeded; cookie={}", enuCookie.get());
                    } else {
                        log.warn("Initial ENU login failed");
                    }
                }, error -> log.error("Error during initial ENU login: {}", error.getMessage()));
    }

    @Override
    public Mono<Boolean> ensureAuthenticated() {
        if (enuCookie.get() != null) {
            return Mono.just(true);
        }

        if (loginInProgress.get()) {
            return loginNotifier.asFlux()
                    .next()
                    .flatMap(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            return Mono.just(true);
                        } else {
                            return Mono.error(new IllegalStateException("ENU login failed"));
                        }
                    });
        }

        loginInProgress.set(true);
        loginNotifier.emitNext(false, Sinks.EmitFailureHandler.FAIL_FAST);

        EnuPortalLoginRequest loginRequest = new EnuPortalLoginRequest("1", username, password);

        return loginWebClient.post()
                .uri("/get/get_auth.php")
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .bodyValue(buildFormData(loginRequest))
                .exchangeToMono(this::extractCookie)
                .timeout(Duration.ofSeconds(5))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(ex -> true)
                        .doBeforeRetry(signal -> log.warn(
                                "Retry ENU login because: {}", signal.failure().getMessage()
                        ))
                )
                .flatMap(cookieValue -> {
                    if (cookieValue != null && !cookieValue.isBlank()) {
                        enuCookie.set(cookieValue);
                        loginNotifier.emitNext(true, Sinks.EmitFailureHandler.FAIL_FAST);
                        log.info("ENU login succeeded; cookie={}", cookieValue);
                        return Mono.just(true);
                    } else {
                        loginNotifier.emitNext(false, Sinks.EmitFailureHandler.FAIL_FAST);
                        log.error("ENU login returned empty cookie");
                        return Mono.error(new IllegalStateException("ENU login returned no cookie"));
                    }
                })
                .doFinally(signal -> loginInProgress.set(false));
    }

    private MultiValueMap<String, String> buildFormData(EnuPortalLoginRequest req) {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("json", req.json());
        form.add("auth_username", req.username());
        form.add("auth_password", req.password());
        return form;
    }

    private Mono<String> extractCookie(ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .flatMap(body -> {
                    try {
                        var root = new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(body);
                        boolean result = root.path("result").asBoolean(false);
                        boolean error = root.path("error").asBoolean(false);
                        if (!result || error) {
                            return Mono.error(new IllegalStateException("ENU login failed JSON: " + body));
                        }
                    } catch (Exception ex) {
                        return Mono.error(new IllegalStateException("Error parsing ENU login JSON: " + ex.getMessage(), ex));
                    }
                    String cookieName = "PORTALENUKZ-SID";
                    if (resp.cookies().containsKey(cookieName)) {
                        return Mono.just(resp.cookies().getFirst(cookieName).getValue());
                    }
                    return resp.headers().header(HttpHeaders.SET_COOKIE).stream()
                            .filter(h -> h.startsWith(cookieName + "="))
                            .findFirst()
                            .map(raw -> {
                                int idx = raw.indexOf('=');
                                int semicolon = raw.indexOf(';');
                                return raw.substring(idx + 1,
                                        semicolon > 0 ? semicolon : raw.length());
                            })
                            .map(Mono::just)
                            .orElseGet(() -> Mono.error(
                                    new IllegalStateException("Login response missing cookie")
                            ));
                });
    }

    @Override
    public String getCookieValue() {
        return enuCookie.get();
    }

    @Override
    public ExchangeFilterFunction authenticationFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            String path = clientRequest.url().getPath();
            if (path.contains("/get/get_auth.php")) {
                return Mono.just(clientRequest);
            }
            return ensureAuthenticated()
                    .flatMap(ok -> {
                        String cookie = enuCookie.get();
                        if (cookie != null && !cookie.isBlank()) {
                            ClientRequest newRequest = ClientRequest.from(clientRequest)
                                    .header(HttpHeaders.COOKIE, "PORTALENUKZ-SID=" + cookie)
                                    .build();
                            return Mono.just(newRequest);
                        }
                        return Mono.just(clientRequest);
                    });
        }).andThen(ExchangeFilterFunction.ofResponseProcessor(response -> {
            HttpStatusCode status = response.statusCode();
            if (status.is4xxClientError()) {
                invalidateSession();
                log.warn("ENU returned {} → invalidating session and throwing UnauthorizedException", status);
                return Mono.error(new UnauthorizedException("ENU returned status " + status));
            }
            return Mono.just(response);
        }));
    }

    @Override
    public void invalidateSession() {
        enuCookie.set(null);
    }
}
