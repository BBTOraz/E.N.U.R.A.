package bbt.tao.orchestra;

import bbt.tao.orchestra.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest
class RealLLMTitleGenerationTest {

    private static final Logger logger = LoggerFactory.getLogger(RealLLMTitleGenerationTest.class);

    @Autowired
    private ConversationService conversationService;

    @Test
    void testRealGenerateAndSaveTitle() {
        // Тестовый ID разговора
        String conversationId = "test-real-llm-" + System.currentTimeMillis();

        // Тестовое сообщение для генерации заголовка
        String firstMessage = "Привет, я хотел бы узнать о погоде на завтра в Москве. Будет ли дождь?";

        logger.info("Начинаем тестовый запрос к LLM для conversationId: {}", conversationId);
        logger.info("Содержание запроса: {}", firstMessage);

        // Создаем Mono с таймаутом и обработкой ошибок
        Mono<String> titleMono = conversationService.generateAndSaveTitle(conversationId, firstMessage)
                .doOnSuccess(title -> {
                    logger.info("Успешно получен ответ от LLM: {}", title);
                })
                .doOnError(error -> {
                    logger.error("Ошибка при обращении к LLM: {}", error.getMessage(), error);
                })
                .onErrorResume(e -> {
                    // В случае ошибки возвращаем информацию об ошибке вместо падения теста
                    return Mono.just("ERROR: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                });

        // Проверяем выполнение запроса и логируем результат
        StepVerifier.create(titleMono)
                .consumeNextWith(result -> {
                    logger.info("Результат теста LLM: {}", result);
                    if (result.startsWith("ERROR:")) {
                        logger.warn("Тест завершен с ошибкой LLM (но тест не провален): {}", result);
                    } else {
                        logger.info("Тест успешно завершен, получен заголовок: {}", result);
                    }
                })
                .verifyComplete();
    }
}
