package bbt.tao.orchestra.service.client;

import bbt.tao.orchestra.dto.enu.portal.DictionaryStaffInfoRequest;
import bbt.tao.orchestra.dto.enu.portal.EnuStaffSearchResponse;
import bbt.tao.orchestra.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;

import static org.springframework.ai.util.json.JsonParser.toJson;

@Slf4j
@Service
public class EnuPortalApiClient {

    private final WebClient webClient;

    @Value("${enu.portal.base-url}")
    private String portalPath;

    public EnuPortalApiClient(@Qualifier("enuPortalWebClient") WebClient webClient) {
        this.webClient = webClient;
    }

    private DictionaryStaffInfoRequest buildStaffInfoRequest(DictionaryStaffInfoRequest request) {
        return new DictionaryStaffInfoRequest(
                "staff",
                nonNullOrEmpty(request.iin()),
                nonNullOrEmpty(request.lastname()),
                nonNullOrEmpty(request.middlename()),
                nonNullOrEmpty(request.firstname()),
                request.building(),
                request.room(),
                nonNullOrEmpty(request.phone()),
                nonNullOrEmpty(request.login()),
                request.staff_unitname(),
                request.staff_postname(),
                request.tutor_unitname(),
                request.tutor_postname(),
                request.fired()   != null ? request.fired()   : 0L,
                request.not_fired()!= null ? request.not_fired(): 1L
        );
    }

    private String nonNullOrEmpty(String s) {
        return (s != null && !s.isBlank()) ? s : "";
    }

    public EnuStaffSearchResponse getStaffInfo(DictionaryStaffInfoRequest request) throws ExecutionException, InterruptedException {
        String jsonValue = toJson(buildStaffInfoRequest(request));
        String encoded = URLEncoder.encode(jsonValue, StandardCharsets.UTF_8);
        log.info("getStaffInfo: {}", jsonValue);


        return webClient.get()
                .uri(builder -> UriComponentsBuilder
                        .fromHttpUrl(portalPath)
                        .path("/request.php")
                        .queryParam("request", "get_dictonary")
                        .queryParam("query", "formSearch")
                        .queryParam("value", encoded)
                        .queryParam("json", "1")
                        .build(true).toUri())
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(EnuStaffSearchResponse.class)
                .doOnSubscribe(subscription -> log.info("EnuPortalApiClient.getStaffInfo: Подписка на bodyToMono"))
                .doOnNext(responseObject -> {
                    System.out.println("webClient do on next chain: " + responseObject);
                    if (responseObject != null) {
                        log.info("EnuPortalApiClient.getStaffInfo: doOnNext ПОЛУЧИЛ EnuStaffSearchResponse. Количество сотрудников: {}",
                                responseObject.members() != null ? responseObject.members().size() : "members is null");
                    } else {
                        log.warn("EnuPortalApiClient.getStaffInfo: doOnNext ПОЛУЧИЛ null EnuStaffSearchResponse (ЭТОГО БЫТЬ НЕ ДОЛЖНО С bodyToMono!).");
                    }
                })
                .retryWhen(Retry.backoff(1, Duration.ofMillis(500))
                        .filter(ex -> ex instanceof UnauthorizedException)
                        .doBeforeRetry(sig -> log.warn("Retry ENU getStaffInfo after Unauthorized"))
                )
                .doOnError(err -> log.error("Error calling ENU getStaffInfo: {}", err.getMessage()))
                .onErrorResume(UnauthorizedException.class, e -> {
                    log.error("Unauthorized access to ENU portal: {}", e.getMessage());
                    System.out.println("Unauthorized access to ENU portal: " + e.getMessage());
                    return Mono.error(new UnauthorizedException("Unauthorized access to ENU portal. Please check your session."));
                })
                .subscribeOn(Schedulers.boundedElastic()).toFuture().get();
    }
}
