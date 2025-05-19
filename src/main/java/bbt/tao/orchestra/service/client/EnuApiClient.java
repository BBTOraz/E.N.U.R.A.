package bbt.tao.orchestra.service.client;

import bbt.tao.orchestra.dto.enu.DictionaryStaffInfoRequest;
import bbt.tao.orchestra.dto.enu.EnuLoginRequest;
import bbt.tao.orchestra.dto.enu.EnuStaffSearchResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.ai.util.json.JsonParser.toJson;

@Slf4j
@Service
public class EnuApiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    private static final String ENU_COOKIE_NAME = "PORTALENUKZ-SID";
    private final AtomicReference<String> cookieValue = new AtomicReference<>();

    @Value("${enu.portal.base-url}")
    private String baseUrl;

    public EnuApiClient(@Qualifier("enuPortalWebClient") WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    public String getCookieValue() {
        return cookieValue.get();
    }

    public Mono<Boolean> login(EnuLoginRequest request) {
        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("json", request.json());
        map.add("auth_username", request.username());
        map.add("auth_password", request.password());

        log.info("Попытка авторизации в ENU API: {}", request.username());

        return webClient.post()
                .uri("get/get_auth.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(map))
                .exchangeToMono(clientResponse ->{
                    extractAndStoreCookie(clientResponse);
                    return clientResponse.bodyToMono(String.class);
                })
                .flatMap(response -> {
                    try {
                        JsonNode authResponse =objectMapper.readTree(response);
                        boolean result = authResponse.get("result").asBoolean(false);
                        boolean hasErrorField = authResponse.hasNonNull("error");
                        boolean errorFlag = hasErrorField && authResponse.path("error").asBoolean(true);

                        if (result && !errorFlag) {
                            log.info("ENU Portal login successful for user: {}. Session cookies should be set.", request.username());
                        return Mono.just(true);
                        } else {
                            String connectMsg = authResponse.path("connect").asText("N/A");
                            log.warn("ENU Portal login failed for user: {}. API Response: result={}, error={}, connect_msg={}",
                                request.username(), result, authResponse.path("error").asText("N/A"), connectMsg);
                        return Mono.just(false);
                        }
                    } catch (JsonProcessingException e) {
                        log.error("Error parsing ENU login response JSON: {}", request, e);
                        return Mono.just(false);
                    }
                })
                .doOnError(error -> log.error("ENU Login request failed at network/http level for user: {}", request.username(), error))
                .defaultIfEmpty(false);
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
    private void extractAndStoreCookie(ClientResponse response) {
        MultiValueMap<String, ResponseCookie> cookies = response.cookies();
        if (cookies.containsKey(ENU_COOKIE_NAME)) {
            ResponseCookie cookie = cookies.getFirst(ENU_COOKIE_NAME);
            if (cookie != null) {
                String newCookieValue = cookie.getValue();
                cookieValue.set(newCookieValue);
                log.debug("Extracted and stored ENU cookie: {}", newCookieValue);
            }
        } else {
            response.headers().asHttpHeaders().getValuesAsList(HttpHeaders.SET_COOKIE)
                    .stream()
                    .filter(cookie -> cookie.startsWith(ENU_COOKIE_NAME))
                    .findFirst()
                    .ifPresent(cookie -> {
                        String value = cookie.substring(cookie.indexOf('=') + 1,
                                cookie.indexOf(';') > 0 ? cookie.indexOf(';') : cookie.length());
                        cookieValue.set(value);
                        log.debug("Extracted and stored ENU cookie from header: {}", value);
                    });
        }
    }

    //todo исправить ошибку с адресом
    public Mono<EnuStaffSearchResponse> getStaffInfo(DictionaryStaffInfoRequest request) {
        var staffInfoRequest = buildStaffInfoRequest(request);

        log.info("Sending request to ENU API: {}", toJson(staffInfoRequest));

        String encodedValueJson = URLEncoder.encode(toJson(staffInfoRequest), StandardCharsets.UTF_8);


        URI finalUri = UriComponentsBuilder.fromHttpUrl(baseUrl) 
                .path("/request.php")
                .queryParam("request", "get_dictonary")
                .queryParam("query", "formSearch")
                .query("value=" + encodedValueJson)
                .queryParam("json", "1")
                .build(true)
                .toUri();

        log.info("Constructed ENU API URI (Manual Encoding): {}", finalUri);

        WebClient.RequestHeadersSpec<?> requestHeadersSpec = webClient
                .get()
                .uri(finalUri)
                .accept(MediaType.APPLICATION_JSON);

        if(cookieValue.get() != null) {
            requestHeadersSpec = requestHeadersSpec.cookie(ENU_COOKIE_NAME, cookieValue.get());
        }

        return requestHeadersSpec
                .retrieve()
                .bodyToMono(EnuStaffSearchResponse.class)
                .doOnSuccess(response -> log.debug("Successfully received response from ENU API for request: {}", response))
                .doOnError(error -> log.error("Error response from ENU API for request: {}", request, error));
    }
}
