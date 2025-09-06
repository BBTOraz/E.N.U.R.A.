package bbt.tao.orchestra;

import bbt.tao.orchestra.entity.ConversationTitle;
import bbt.tao.orchestra.entity.MessageEntity;
import bbt.tao.orchestra.repository.ConversationRepository;
import bbt.tao.orchestra.repository.MessageRepository;
import bbt.tao.orchestra.service.ConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;


@SpringBootTest
class ConversationServiceTest {

    private MessageRepository messageRepository;
    private ConversationRepository conversationRepository;
    private ChatClient chatClient;
    private ConversationService conversationService;


    @BeforeEach
    void setUp() {
        messageRepository = Mockito.mock(MessageRepository.class);
        conversationRepository = Mockito.mock(ConversationRepository.class);
        chatClient = Mockito.mock(ChatClient.class);

        conversationService = new ConversationService(
                conversationRepository,
                messageRepository,
                chatClient
        );
    }

    @Test
    void testGetHistoryPerformance() {
        String conversationId = "test-conversation";
        int expectedMessageCount = 1000;

        List<MessageEntity> messages = new ArrayList<>();
        for (int i = 0; i < expectedMessageCount; i++) {
            messages.add(new MessageEntity(
                    conversationId,
                    "Сообщение " + i,
                    i % 2 == 0 ? "USER" : "AI",
                    LocalDateTime.now().plusSeconds(1)
            ));
        }

        when(messageRepository.findAllByConversationIdOrderByTimestampAsc(conversationId))
                .thenReturn(Flux.fromIterable(messages));

        // Измерение времени и подсчет элементов
        long startTime = System.currentTimeMillis();
        AtomicInteger actualCount = new AtomicInteger(0);

        StepVerifier.create(
                        conversationService.getHistory(conversationId)
                                .doOnNext(m -> actualCount.incrementAndGet())
                )
                .expectNextCount(expectedMessageCount)
                .verifyComplete();

        long endTime = System.currentTimeMillis();

        // Вывод результатов
        System.out.println("Всего получено сущностей: " + actualCount.get());
        System.out.println("Время выполнения: " + (endTime - startTime) + " мс");

        assertThat(actualCount.get()).isEqualTo(expectedMessageCount);
    }

    @Test
    void testGenerateAndSaveTitle() {
        // Подготовка тестовых данных
        String conversationId = "default";
        String firstMessage = "Привет, я хотел бы узнать о погоде на завтра";
        String generatedTitle = "Прогноз погоды";

        // Настройка моков
        var promptBuilder = Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        var userPromptBuilder = Mockito.mock(ChatClient.ChatClientRequestSpec.class);
        var response = Mockito.mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(promptBuilder);
        when(promptBuilder.user(Mockito.anyString())).thenReturn(userPromptBuilder);
        when(userPromptBuilder.call()).thenReturn(response);
        when(response.content()).thenReturn(generatedTitle);

        when(conversationRepository.save(Mockito.any(ConversationTitle.class)))
                .thenReturn(Mono.just(new ConversationTitle(conversationId, generatedTitle)));

        // Запуск тестируемого метода
        StepVerifier.create(conversationService.generateAndSaveTitle(conversationId, firstMessage))
                .expectNext(generatedTitle)
                .verifyComplete();

        // Проверка, что правильный запрос был отправлен на ChatClient
        Mockito.verify(promptBuilder).user("Придумай лаконичный заголовок для беседы: " + firstMessage);

        // Проверка, что заголовок был сохранен в базе данных
        Mockito.verify(conversationRepository).save(new ConversationTitle(conversationId, generatedTitle));
    }
}

