package bbt.tao.orchestra;

import bbt.tao.orchestra.conf.OpenAiRealtimeProperties;
import bbt.tao.orchestra.service.OpenAiRealtimeSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRealtimeSessionServiceTest {

    @Test
    void createsEphemeralRealtimeSession() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedRequest.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("""
                                    {
                                      "value": "ek_test_client_secret",
                                      "expires_at": 1893456000
                                    }
                                    """)
                            .build());
                });
        OpenAiRealtimeSessionService service = new OpenAiRealtimeSessionService(properties("sk-test"), webClientBuilder);

        StepVerifier.create(service.createSession())
                .assertNext(session -> {
                    assertThat(session.clientSecret()).isEqualTo("ek_test_client_secret");
                    assertThat(session.expiresAt()).isEqualTo(1893456000L);
                    assertThat(session.model()).isEqualTo("gpt-realtime");
                    assertThat(session.voice()).isEqualTo("marin");
                })
                .verifyComplete();

        ClientRequest request = capturedRequest.get();
        assertThat(request.method().name()).isEqualTo("POST");
        assertThat(request.url().toString()).isEqualTo("https://api.openai.test/v1/realtime/client_secrets");
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-test");
    }

    @Test
    void supportsNestedClientSecretShape() {
        WebClient.Builder webClientBuilder = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "client_secret": {
                                    "value": "ek_nested_client_secret",
                                    "expires_at": 1893456001
                                  }
                                }
                                """)
                        .build()));
        OpenAiRealtimeSessionService service = new OpenAiRealtimeSessionService(properties("sk-test"), webClientBuilder);

        StepVerifier.create(service.createSession())
                .assertNext(session -> {
                    assertThat(session.clientSecret()).isEqualTo("ek_nested_client_secret");
                    assertThat(session.expiresAt()).isEqualTo(1893456001L);
                })
                .verifyComplete();
    }

    @Test
    void rejectsMissingOpenAiApiKey() {
        OpenAiRealtimeSessionService service = new OpenAiRealtimeSessionService(properties(""), WebClient.builder());

        StepVerifier.create(service.createSession())
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(ResponseStatusException.class);
                    assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                })
                .verify();
    }

    private OpenAiRealtimeProperties properties(String apiKey) {
        OpenAiRealtimeProperties properties = new OpenAiRealtimeProperties();
        properties.setApiKey(apiKey);
        properties.setBaseUrl("https://api.openai.test");
        properties.setModel("gpt-realtime");
        properties.setVoice("marin");
        properties.setNoiseReduction("near_field");
        properties.setTurnDetectionType("server_vad");
        properties.setInstructions("Answer in Russian unless the user speaks Kazakh.");
        return properties;
    }
}
