package bbt.tao.orchestra;

import bbt.tao.orchestra.entity.ConversationTitle;
import bbt.tao.orchestra.observability.OrchestraTelemetry;
import bbt.tao.orchestra.repository.ConversationRepository;
import bbt.tao.orchestra.repository.MessageRepository;
import bbt.tao.orchestra.service.ConversationService;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class ConversationServiceObservabilityTest {

    private ConversationRepository conversationRepository;
    private ConversationService conversationService;
    private SimpleMeterRegistry meterRegistry;
    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        MessageRepository messageRepository = mock(MessageRepository.class);
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        meterRegistry = new SimpleMeterRegistry();
        conversationService = new ConversationService(
                conversationRepository,
                messageRepository,
                chatClient,
                new OrchestraTelemetry(meterRegistry)
        );
    }

    @Test
    void recordsSuccessfulTitleGenerationTiming(CapturedOutput output) {
        when(conversationRepository.existsById("conv-1")).thenReturn(Mono.just(false));
        when(chatClient.prompt().user(anyString()).call().content()).thenReturn("Generated title");
        when(conversationRepository.insertTitle("conv-1", "Generated title")).thenReturn(Mono.empty());

        StepVerifier.create(conversationService.generateAndSaveTitle("conv-1", "First message"))
                .expectNext("Generated title")
                .verifyComplete();

        Timer timer = meterRegistry.get("orchestra_conversation_title_generation_seconds")
                .tag("status", "success")
                .timer();

        assertThat(timer.count()).isEqualTo(1L);
        verify(conversationRepository).insertTitle("conv-1", "Generated title");
        assertThat(output.getOut()).doesNotContain("event=application_error");
    }

    @Test
    void recordsFailedTitleGenerationTimingAndError(CapturedOutput output) {
        when(conversationRepository.existsById("conv-2")).thenReturn(Mono.just(false));
        when(chatClient.prompt().user(anyString()).call().content()).thenThrow(new IllegalStateException("title failed"));

        StepVerifier.create(conversationService.generateAndSaveTitle("conv-2", "First message"))
                .expectError(IllegalStateException.class)
                .verify();

        Timer timer = meterRegistry.get("orchestra_conversation_title_generation_seconds")
                .tag("status", "error")
                .timer();

        assertThat(timer.count()).isEqualTo(1L);
        assertThat(output.getOut())
                .contains("event=application_error")
                .contains("errorClass=IllegalStateException")
                .contains("context=conversation_title_generation")
                .contains("conversationId=conv-2");
    }
}
