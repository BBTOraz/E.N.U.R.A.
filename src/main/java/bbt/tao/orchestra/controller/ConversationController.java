package bbt.tao.orchestra.controller;

import bbt.tao.orchestra.dto.TitleGenerationRequest;
import bbt.tao.orchestra.entity.ConversationTitle;
import bbt.tao.orchestra.entity.MessageEntity;
import bbt.tao.orchestra.service.ConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {

    private final ConversationService conversationService;

    @Autowired
    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /**
     * Получение списка всех заголовков разговоров
     * @return список заголовков разговоров
     */
    @GetMapping
    public Mono<ResponseEntity<List<ConversationTitle>>> getAllConversationTitles() {
        return conversationService.getAllConversationTitles()
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }

    /**
     * Получение истории сообщений для конкретного разговора
     * @param conversationId идентификатор разговора
     * @return поток сообщений в хронологическом порядке
     */
    @GetMapping("/{conversationId}/messages")
    public Flux<MessageEntity> getConversationHistory(@PathVariable String conversationId) {
        return conversationService.getHistory(conversationId);
    }

    /**
     * Получение заголовка конкретного разговора
     * @param conversationId идентификатор разговора
     * @return заголовок разговора
     */
    @GetMapping("/{conversationId}/title")
    public Mono<ResponseEntity<ConversationTitle>> getConversationTitle(@PathVariable String conversationId) {
        return conversationService.getConversationTitle(conversationId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Автоматическое создание заголовка для разговора на основе первого сообщения
     * @param conversationId идентификатор разговора
     * @param request запрос с текстом первого сообщения
     * @return сгенерированный заголовок
     */
    @PostMapping("/{conversationId}/generate-title")
    public Mono<ResponseEntity<String>> generateTitle(
            @PathVariable String conversationId,
            @RequestBody TitleGenerationRequest request) {

        if (!request.isValid()) {
            return Mono.just(ResponseEntity.badRequest().body("Сообщение не может быть пустым"));
        }

        return conversationService.generateAndSaveTitle(conversationId, request.message())
                .map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(
                    ResponseEntity.internalServerError().body("Не удалось создать заголовок: " + e.getMessage())
                ));
    }
}
