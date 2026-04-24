package bbt.tao.orchestra.service;

import bbt.tao.orchestra.conf.OpenAiRealtimeProperties;
import bbt.tao.orchestra.dto.voice.RealtimeSessionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OpenAiRealtimeSessionService {

    private final OpenAiRealtimeProperties properties;
    private final WebClient webClient;

    public OpenAiRealtimeSessionService(OpenAiRealtimeProperties properties, WebClient.Builder webClientBuilder) {
        this.properties = properties;
        this.webClient = webClientBuilder.clone()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    public Mono<RealtimeSessionResponse> createSession() {
        if (!StringUtils.hasText(properties.getApiKey())) {
            return Mono.error(new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OPENAI_API_KEY is not configured"
            ));
        }

        return webClient.post()
                .uri("/v1/realtime/client_secrets")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .bodyValue(buildSessionRequest())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(this::toResponse)
                .onErrorMap(WebClientResponseException.class, error -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "OpenAI Realtime session request failed",
                        error
                ));
    }

    private Map<String, Object> buildSessionRequest() {
        Map<String, Object> inputAudio = new LinkedHashMap<>();
        if (StringUtils.hasText(properties.getNoiseReduction())) {
            inputAudio.put("noise_reduction", Map.of("type", properties.getNoiseReduction()));
        }
        if (StringUtils.hasText(properties.getTurnDetectionType())) {
            inputAudio.put("turn_detection", Map.of(
                    "type", properties.getTurnDetectionType(),
                    "create_response", true,
                    "interrupt_response", true
            ));
        }

        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("output", Map.of("voice", properties.getVoice()));
        audio.put("input", inputAudio);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("type", "realtime");
        session.put("model", properties.getModel());
        session.put("instructions", properties.getInstructions());
        session.put("audio", audio);

        return Map.of("session", session);
    }

    private RealtimeSessionResponse toResponse(JsonNode root) {
        JsonNode clientSecret = root.path("client_secret");
        String value = firstText(root.path("value"), clientSecret.path("value"));
        Long expiresAt = firstLong(root.path("expires_at"), clientSecret.path("expires_at"));

        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI Realtime session response did not include a client secret"
            );
        }

        return new RealtimeSessionResponse(value, expiresAt, properties.getModel(), properties.getVoice());
    }

    private String firstText(JsonNode first, JsonNode second) {
        if (first.isTextual() && StringUtils.hasText(first.asText())) {
            return first.asText();
        }
        if (second.isTextual() && StringUtils.hasText(second.asText())) {
            return second.asText();
        }
        return "";
    }

    private Long firstLong(JsonNode first, JsonNode second) {
        if (first.isNumber()) {
            return first.asLong();
        }
        if (second.isNumber()) {
            return second.asLong();
        }
        return null;
    }
}
