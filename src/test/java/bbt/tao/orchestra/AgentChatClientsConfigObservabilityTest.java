package bbt.tao.orchestra;

import bbt.tao.orchestra.conf.AgentChatClientsConfig;
import bbt.tao.orchestra.observability.OrchestraTelemetry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AgentChatClientsConfigObservabilityTest {

    @Test
    void recordsMetricsWhenRestClientBuilderIsUsed(CapturedOutput output) throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentChatClientsConfig config = new AgentChatClientsConfig(new OrchestraTelemetry(meterRegistry));

        try (LocalTestServer server = LocalTestServer.start()) {
            Method method = AgentChatClientsConfig.class.getDeclaredMethod(
                    "createRestClientBuilder",
                    String.class,
                    Function.class
            );
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            RestClient.Builder builder = (RestClient.Builder) method.invoke(
                    config,
                    "open-router",
                    Function.<HttpHeaders>identity()
            );

            RestClient client = builder.baseUrl(server.baseUrl()).build();
            String body = client.get().uri("/ok").retrieve().body(String.class);

            Counter counter = meterRegistry.get("orchestra_provider_calls_total")
                    .tag("provider", "open-router")
                    .tag("operation", "rest_client")
                    .tag("status", "success")
                    .counter();
            Timer timer = meterRegistry.get("orchestra_provider_latency_seconds")
                    .tag("provider", "open-router")
                    .tag("operation", "rest_client")
                    .timer();

            assertThat(body).isEqualTo("ok");
            assertThat(counter.count()).isEqualTo(1.0d);
            assertThat(timer.count()).isEqualTo(1L);
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive();
            assertThat(output.getOut())
                    .contains("event=provider_call_completed")
                    .contains("provider=open-router")
                    .contains("operation=rest_client")
                    .contains("status=success");
        }
    }

    @Test
    void recordsMetricsWhenWebClientBuilderIsUsed(CapturedOutput output) throws Exception {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentChatClientsConfig config = new AgentChatClientsConfig(new OrchestraTelemetry(meterRegistry));

        try (LocalTestServer server = LocalTestServer.start()) {
            Method method = AgentChatClientsConfig.class.getDeclaredMethod("createWebClientBuilder", String.class);
            method.setAccessible(true);

            WebClient.Builder builder = (WebClient.Builder) method.invoke(config, "open-router");
            WebClient client = builder.baseUrl(server.baseUrl()).build();

            Integer statusCode = client.get()
                    .uri("/ok")
                    .retrieve()
                    .toBodilessEntity()
                    .map(response -> response.getStatusCode().value())
                    .block();

            Counter counter = meterRegistry.get("orchestra_provider_calls_total")
                    .tag("provider", "open-router")
                    .tag("operation", "web_client")
                    .tag("status", "success")
                    .counter();
            Timer timer = meterRegistry.get("orchestra_provider_latency_seconds")
                    .tag("provider", "open-router")
                    .tag("operation", "web_client")
                    .timer();

            assertThat(statusCode).isEqualTo(200);
            assertThat(counter.count()).isEqualTo(1.0d);
            assertThat(timer.count()).isEqualTo(1L);
            assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isPositive();
            assertThat(output.getOut())
                    .contains("event=provider_call_completed")
                    .contains("provider=open-router")
                    .contains("operation=web_client")
                    .contains("status=success");
        }
    }

    private static final class LocalTestServer implements AutoCloseable {

        private final HttpServer server;

        private LocalTestServer(HttpServer server) {
            this.server = server;
        }

        static LocalTestServer start() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/ok", LocalTestServer::writeOk);
            server.start();
            return new LocalTestServer(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void writeOk(HttpExchange exchange) throws IOException {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(body);
            } finally {
                exchange.close();
            }
        }
    }
}
