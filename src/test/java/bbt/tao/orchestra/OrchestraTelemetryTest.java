package bbt.tao.orchestra;

import bbt.tao.orchestra.observability.OrchestraTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class OrchestraTelemetryTest {

    private SimpleMeterRegistry meterRegistry;
    private OrchestraTelemetry telemetry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        telemetry = new OrchestraTelemetry(meterRegistry);
    }

    @Test
    void recordsProviderMetricsAndStructuredLog(CapturedOutput output) {
        telemetry.recordProviderCall("open-router", "chat_client", "success", Duration.ofMillis(125));

        Counter counter = meterRegistry.get("orchestra_provider_calls_total")
                .tag("provider", "open-router")
                .tag("operation", "chat_client")
                .tag("status", "success")
                .counter();
        Timer timer = meterRegistry.get("orchestra_provider_latency_seconds")
                .tag("provider", "open-router")
                .tag("operation", "chat_client")
                .timer();

        assertThat(counter.count()).isEqualTo(1.0d);
        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(125.0d);
        assertThat(output.getOut())
                .contains("event=provider_call_completed")
                .contains("provider=open-router")
                .contains("operation=chat_client")
                .contains("status=success")
                .contains("latencyMs=125");
    }

    @Test
    void recordsSseMetricsAndStructuredLogs(CapturedOutput output) {
        telemetry.logConversationMessageSubmitted("conv-1", "stream", 11);
        telemetry.recordSseSession("opened");
        telemetry.recordSseSession("completed");
        telemetry.logSseStreamClosed("conv-1", "completed", Duration.ofMillis(350));

        Counter openedCounter = meterRegistry.get("orchestra_sse_sessions_total")
                .tag("status", "opened")
                .counter();
        Counter completedCounter = meterRegistry.get("orchestra_sse_sessions_total")
                .tag("status", "completed")
                .counter();

        assertThat(openedCounter.count()).isEqualTo(1.0d);
        assertThat(completedCounter.count()).isEqualTo(1.0d);
        assertThat(output.getOut())
                .contains("event=conversation_message_submitted")
                .contains("conversationId=conv-1")
                .contains("mode=stream")
                .contains("messageLength=11")
                .contains("event=sse_stream_closed")
                .contains("status=completed")
                .contains("durationMs=350");
    }

    @Test
    void recordsTitleGenerationAndApplicationError(CapturedOutput output) {
        telemetry.recordConversationTitleGeneration("error", Duration.ofMillis(40));
        telemetry.logApplicationError(new IllegalStateException("boom"), "conversation_title_generation", "conv-99");

        Timer timer = meterRegistry.get("orchestra_conversation_title_generation_seconds")
                .tag("status", "error")
                .timer();

        assertThat(timer.count()).isEqualTo(1L);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(40.0d);
        assertThat(output.getOut())
                .contains("event=application_error")
                .contains("errorClass=IllegalStateException")
                .contains("context=conversation_title_generation")
                .contains("conversationId=conv-99");
    }
}
