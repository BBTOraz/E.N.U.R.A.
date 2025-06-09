package bbt.tao.orchestra.manager.init;

import bbt.tao.orchestra.dto.enu.platonus.PlatonusLoginRequest;
import bbt.tao.orchestra.exception.UnauthorizedException;
import bbt.tao.orchestra.manager.ApiAuthenticator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
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
public class PlatonusApiAuthenticator implements ApiAuthenticator {

    private final WebClient webClient;
    private final String iin;
    private final String password;
    private final ObjectMapper objectMapper;

    private final AtomicReference<String> token = new AtomicReference<>(null);
    private final AtomicReference<String> sid = new AtomicReference<>(null);

    private final AtomicBoolean loginInProgress = new AtomicBoolean(false);
    private final Sinks.Many<Boolean> loginNotifier = Sinks.many().replay().latest();

    public PlatonusApiAuthenticator(WebClient.Builder webClient,
                                    @Value("${enu.api.username}") String iin, @Value("${enu.api.password}") String password,
                                    @Value("${platonus.api.base-url}") String url, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = webClient
                .baseUrl(url)
                .build();
        this.iin = iin;
        this.password = password;
    }

    @PostConstruct
    private void initialLogin() {
        ensureAuthenticated().subscribe(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                log.info("Initial Platonus API login succeeded; sid={}, token={}", sid.get(), token.get());
            } else {
                log.warn("Initial Platonus API login failed");
            }
        }, error -> log.error("Error during initial Platonus login: {}", error.getMessage()));
    }

    @Override
    public Mono<Boolean> ensureAuthenticated() {
        if (token.get() != null && sid.get() != null) {
            return Mono.just(true);
        }

        if (loginInProgress.get()) {
            return loginNotifier.asFlux()
                    .next()
                    .flatMap(success -> {
                        if (Boolean.TRUE.equals(success)) {
                            return Mono.just(true);
                        }
                        return Mono.error(new IllegalStateException("Platonus login failed"));
                    });
        }

        loginInProgress.set(true);
        loginNotifier.emitNext(false, Sinks.EmitFailureHandler.FAIL_FAST);

        PlatonusLoginRequest loginRequest = new PlatonusLoginRequest(iin, password);
        return webClient.post()
                .uri("/rest/api/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(loginRequest)
                .exchangeToMono(this::parseAndStoreToken)
                .timeout(Duration.ofSeconds(5))
                .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                        .filter(ex -> true)
                        .doBeforeRetry(sig -> log.warn("Retrying login due to: {}", sig.failure().getMessage())))
                .flatMap(success -> {
                    if(success){
                        loginNotifier.emitNext(true, Sinks.EmitFailureHandler.FAIL_FAST);
                        log.info("Platonus API login succeeded; sid={}, token={}", sid.get(), token.get());
                        return Mono.just(true);
                    }else {
                        loginNotifier.emitNext(false, Sinks.EmitFailureHandler.FAIL_FAST);
                        return Mono.error(new IllegalStateException("Platonus login failed"));
                    }
                })
                .doFinally(signalType -> loginInProgress.set(false));

    }

    @Override
    public ExchangeFilterFunction authenticationFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(clientRequest -> {
            String path = clientRequest.url().getPath();
            if (path.contains("/rest/api/login")) {
                return Mono.just(clientRequest);
            }
            return ensureAuthenticated()
                    .flatMap(ok -> {
                        String tokenVal = token.get();
                        String sidVal = sid.get();
                        if (tokenVal != null && sidVal != null) {
                            ClientRequest newReq = ClientRequest.from(clientRequest)
                                    .header( "Token",tokenVal)
                                    .header("sid",sidVal)
                                    .build();
                            return Mono.just(newReq);
                        }
                        return Mono.just(clientRequest);
                    });
        }).andThen(ExchangeFilterFunction.ofResponseProcessor(response -> {
            HttpStatusCode status = response.statusCode();
            if (status.is4xxClientError()) {
                invalidateSession();
                log.warn("Schedule API returned {} → invalidating session", status);
                return Mono.error(new UnauthorizedException("Schedule API returned status " + status));
            }
            return Mono.just(response);
        }));
    }

    @Override
    public void invalidateSession() {
        token.set(null);
        sid.set(null);
    }

    @Override
    public String getCookieValue() {
        return sid.get();
    }

    private Mono<Boolean> parseAndStoreToken(ClientResponse resp) {
        return resp.bodyToMono(String.class)
                .flatMap(body -> {
                    try {
                        JsonNode root = objectMapper.readTree(body);
                        String status = root.path("login_status").asText("");
                        if (!"success".equalsIgnoreCase(status)) {
                            return Mono.error(new IllegalStateException("Platonus login failed JSON: " + body));
                        }
                        String token = root.path("auth_token").asText("");
                        String sidValue = root.path("sid").asText("");
                        if (token.isBlank() || sidValue.isBlank()) {
                            return Mono.error(new IllegalStateException("Platonus login missing token/sid: " + body));
                        }
                        this.token.set(token);
                        sid.set(sidValue);
                        return Mono.just(true);
                    } catch (Exception e) {
                        return Mono.error(new IllegalStateException("Error parsing login JSON: " + e.getMessage(), e));
                    }
                });
    }
}
