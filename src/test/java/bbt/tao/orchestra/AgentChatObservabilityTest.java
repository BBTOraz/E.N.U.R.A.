package bbt.tao.orchestra;

import bbt.tao.orchestra.agent.AgentOrchestrator;
import bbt.tao.orchestra.agent.model.AgentEvent;
import bbt.tao.orchestra.agent.model.AgentRequestContext;
import bbt.tao.orchestra.agent.model.AgentStage;
import bbt.tao.orchestra.agent.model.AgentVisibility;
import bbt.tao.orchestra.conf.AgentProperties;
import bbt.tao.orchestra.controller.AgentChatController;
import bbt.tao.orchestra.observability.OrchestraTelemetry;
import bbt.tao.orchestra.service.ConversationService;
import bbt.tao.orchestra.service.format.Summarizer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class AgentChatObservabilityTest {

    private AgentOrchestrator orchestrator;
    private ConversationService conversationService;
    private AgentChatController controller;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        orchestrator = mock(AgentOrchestrator.class);
        conversationService = mock(ConversationService.class);
        Summarizer summarizer = mock(Summarizer.class);
        AgentProperties properties = new AgentProperties();
        properties.setDefaultProvider("open-router");
        meterRegistry = new SimpleMeterRegistry();
        controller = new AgentChatController(
                orchestrator,
                summarizer,
                properties,
                conversationService,
                new OrchestraTelemetry(meterRegistry)
        );
    }

    @Test
    void recordsCompletionMetricsAndLogsForSseStream(CapturedOutput output) {
        AgentEvent finalAnswer = AgentEvent.of(AgentStage.FINAL_ANSWER, AgentVisibility.HINT, "Answer", "done")
                .withData(Map.of("answer", "assistant reply"));
        when(orchestrator.run(any(AgentRequestContext.class))).thenReturn(Flux.just(finalAnswer));
        when(conversationService.saveConversationPair("conv-1", "hello world", "assistant reply")).thenReturn(Mono.empty());

        Flux<ServerSentEvent<AgentEvent>> response = controller.chat(
                "conv-1",
                Map.of("message", "hello world"),
                null,
                null,
                null,
                "stream",
                null,
                null
        );

        StepVerifier.create(response)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("final_answer");
                    assertThat(event.data().stage()).isEqualTo(AgentStage.FINAL_ANSWER);
                })
                .verifyComplete();

        Counter openedCounter = meterRegistry.get("orchestra_sse_sessions_total")
                .tag("status", "opened")
                .counter();
        Counter completedCounter = meterRegistry.get("orchestra_sse_sessions_total")
                .tag("status", "completed")
                .counter();

        assertThat(openedCounter.count()).isEqualTo(1.0d);
        assertThat(completedCounter.count()).isEqualTo(1.0d);
        verify(conversationService).saveConversationPair("conv-1", "hello world", "assistant reply");
        assertThat(output.getOut())
                .contains("event=conversation_message_submitted")
                .contains("conversationId=conv-1")
                .contains("mode=stream")
                .contains("messageLength=11")
                .contains("event=sse_stream_closed")
                .contains("status=completed");
    }

    @Test
    void recordsErrorMetricsAndApplicationErrorForSseStream(CapturedOutput output) {
        when(orchestrator.run(any(AgentRequestContext.class))).thenReturn(Flux.error(new IllegalStateException("boom")));

        Flux<ServerSentEvent<AgentEvent>> response = controller.chat(
                "conv-2",
                Map.of("message", "failing"),
                null,
                null,
                null,
                "stream",
                null,
                null
        );

        StepVerifier.create(response)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                    assertThat(event.data().stage()).isEqualTo(AgentStage.ERROR);
                    assertThat(event.data().message()).contains("boom");
                })
                .verifyComplete();

        Counter openedCounter = meterRegistry.get("orchestra_sse_sessions_total")
                .tag("status", "opened")
                .counter();
        Counter failedCounter = meterRegistry.get("orchestra_sse_sessions_total")
                .tag("status", "failed")
                .counter();

        assertThat(openedCounter.count()).isEqualTo(1.0d);
        assertThat(failedCounter.count()).isEqualTo(1.0d);
        verify(conversationService, never()).saveConversationPair(any(), any(), any());
        assertThat(output.getOut())
                .contains("event=application_error")
                .contains("errorClass=IllegalStateException")
                .contains("context=agent_chat_stream")
                .contains("conversationId=conv-2")
                .contains("event=sse_stream_closed")
                .contains("status=failed");
    }
}
