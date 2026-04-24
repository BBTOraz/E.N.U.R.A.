package bbt.tao.orchestra;

import bbt.tao.orchestra.entity.MessageType;
import bbt.tao.orchestra.repository.MessageRepository;
import bbt.tao.orchestra.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import java.time.Duration;

@DataR2dbcTest
@Import({ConversationService.class, MessageRepository.class})
@ActiveProfiles("test")
class ConversationServiceTest {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private DatabaseClient databaseClient;

    @MockBean
    private bbt.tao.orchestra.repository.ConversationRepository conversationRepository;

    @MockBean(name = "plainChatClient")
    private org.springframework.ai.chat.client.ChatClient chatClient;

    @BeforeEach
    void clean() {
        // Spring AI creates the table automatically via spring.ai.chat.memory.repository.jdbc.initialize-schema=always
        // Just clean the data for each test
        databaseClient.sql("DELETE FROM spring_ai_chat_memory").fetch().rowsUpdated().block();
    }

    @Test
    void storesConversationPairSuccessfully() {
        String conversationId = "test-" + System.nanoTime();

        StepVerifier.create(
                conversationService.saveConversationPair(conversationId, "user message", "assistant message"))
                .verifyComplete();

        StepVerifier.create(conversationService.getHistory(conversationId))
                .expectNextMatches(entry -> entry.type() == MessageType.USER && entry.content().equals("user message"))
                .expectNextMatches(entry -> entry.type() == MessageType.ASSISTANT && entry.content().equals("assistant message"))
                .verifyComplete();
    }

    @Test
    void doesNotSaveEmptyAssistantMessage() {
        String conversationId = "test-empty-" + System.nanoTime();

        StepVerifier.create(
                conversationService.saveConversationPair(conversationId, "user message", ""))
                .verifyComplete();

        // Не должно быть сохранено ни одно сообщение
        StepVerifier.create(conversationService.getHistory(conversationId))
                .expectNextCount(0)
                .verifyComplete();
    }

    @Test
    void doesNotSaveNullAssistantMessage() {
        String conversationId = "test-null-" + System.nanoTime();

        StepVerifier.create(
                conversationService.saveConversationPair(conversationId, "user message", null))
                .verifyComplete();

        // Не должно быть сохранено ни одно сообщение
        StepVerifier.create(conversationService.getHistory(conversationId))
                .expectNextCount(0)
                .verifyComplete();
    }
}
