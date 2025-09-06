package bbt.tao.orchestra;

import bbt.tao.orchestra.entity.MessageEntity;
import bbt.tao.orchestra.service.ConversationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.sql.init.mode=always",
    "spring.r2dbc.initialization-mode=always"
})
class ConversationServiceIntegrationTest {

    @Autowired
    private ConversationService conversationService;

    @Test
    void testGetHistoryPerformanceWithRealData() {
        String conversationId = "default";

        // Сначала вставим тестовые данные для более точного измерения
        StepVerifier.create(
            conversationService.saveMessage(conversationId, "Тестовое сообщение 1", "USER")
                .then(conversationService.saveMessage(conversationId, "Тестовое сообщение 2", "ASSISTANT"))
        ).verifyComplete();

        AtomicInteger count = new AtomicInteger(0);
        long startTime = System.currentTimeMillis();

        // Используем StepVerifier для корректной работы с Flux
        StepVerifier.create(
            conversationService.getHistory(conversationId)
                .doOnNext(m -> count.incrementAndGet())
                .doOnComplete(() -> {
                    long endTime = System.currentTimeMillis();
                    System.out.println("Получено сущностей: " + count.get());
                    System.out.println("Время выполнения: " + (endTime - startTime) + " мс");
                })
        )
        .thenConsumeWhile(message -> true)
        .verifyComplete();
    }
}
