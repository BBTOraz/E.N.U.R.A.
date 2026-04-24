package bbt.tao.orchestra.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Locale;

@Component
public class OrchestraTelemetry {

    private static final Logger log = LoggerFactory.getLogger(OrchestraTelemetry.class);

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public OrchestraTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.enabled = true;
    }

    private OrchestraTelemetry() {
        this.meterRegistry = null;
        this.enabled = false;
    }

    public static OrchestraTelemetry noop() {
        return new OrchestraTelemetry();
    }

    public void logConversationMessageSubmitted(String conversationId, String mode, int messageLength) {
        if (!enabled) {
            return;
        }
        log.info("event=conversation_message_submitted conversationId={} mode={} messageLength={}",
                conversationId, normalize(mode), Math.max(messageLength, 0));
    }

    public void recordSseSession(String status) {
        if (!enabled) {
            return;
        }
        Counter.builder("orchestra_sse_sessions_total")
                .tag("status", normalize(status))
                .register(meterRegistry)
                .increment();
    }

    public void logSseStreamClosed(String conversationId, String status, Duration duration) {
        if (!enabled) {
            return;
        }
        log.info("event=sse_stream_closed conversationId={} status={} durationMs={}",
                conversationId, normalize(status), safeDuration(duration).toMillis());
    }

    public void recordProviderCall(String provider, String operation, String status, Duration duration) {
        if (!enabled) {
            return;
        }
        Counter.builder("orchestra_provider_calls_total")
                .tag("provider", normalize(provider))
                .tag("operation", normalize(operation))
                .tag("status", normalize(status))
                .register(meterRegistry)
                .increment();
        Timer.builder("orchestra_provider_latency_seconds")
                .tag("provider", normalize(provider))
                .tag("operation", normalize(operation))
                .register(meterRegistry)
                .record(safeDuration(duration));
        log.info("event=provider_call_completed provider={} operation={} status={} latencyMs={}",
                normalize(provider), normalize(operation), normalize(status), safeDuration(duration).toMillis());
    }

    public void recordConversationTitleGeneration(String status, Duration duration) {
        if (!enabled) {
            return;
        }
        Timer.builder("orchestra_conversation_title_generation_seconds")
                .tag("status", normalize(status))
                .register(meterRegistry)
                .record(safeDuration(duration));
    }

    public void logApplicationError(Throwable error, String context, @Nullable String conversationId) {
        if (!enabled) {
            return;
        }
        if (StringUtils.hasText(conversationId)) {
            log.error("event=application_error errorClass={} context={} conversationId={}",
                    error.getClass().getSimpleName(), normalize(context), conversationId, error);
            return;
        }
        log.error("event=application_error errorClass={} context={}",
                error.getClass().getSimpleName(), normalize(context), error);
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Duration safeDuration(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }
}
