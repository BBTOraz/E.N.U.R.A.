package bbt.tao.orchestra.controller;

import bbt.tao.orchestra.dto.api.OrchestraResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.util.json.schema.SpringAiSchemaModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import bbt.tao.orchestra.service.AIService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;


@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AIService geminiService;

    @Autowired
    public ChatController(AIService geminiService) {
        this.geminiService = geminiService;
    }

    @GetMapping(value = "/stream/{conversationId}/{message}")
    public Flux<String> streamChat(@PathVariable String message, @PathVariable String conversationId) {
        log.info("Received stream request: /stream/{}/{}", conversationId, message);
        return geminiService.generateStreaming(conversationId, message)
                .onErrorResume(error -> {
                    log.error("Error in streaming chat for conversation [{}]: {}", conversationId, error.getMessage(), error);
                    return Flux.just("Произошла ошибка при обработке запроса. Пожалуйста, повторите попытку позже.");
                })
                .timeout(java.time.Duration.ofSeconds(30))
                .doOnCancel(() -> log.warn("Stream was canceled for conversation: {}", conversationId))
                .doOnComplete(() -> log.info("Stream completed for conversation: {}", conversationId))
                .doOnError(throwable -> log.error("Stream error occurred: {}", throwable.getMessage()));
    }

    @GetMapping(value = "/simple/{conversationId}/{message}")
    public Mono<ResponseEntity<String>> simpleChat(@PathVariable String message, @PathVariable String conversationId) {
        log.info("Received simple request: /simple/{}", message);
        return geminiService.generate(conversationId, message)
                .map(content -> {
                    log.info("Successfully received simple response");
                    return ResponseEntity.ok().body(content);
                })
                .onErrorResume(error -> {
                    log.error("Error in simpleChat endpoint for message [{}]: {}", message, error.getMessage(), error);
                    return Mono.just(ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Произошла ошибка при обработке запроса. Пожалуйста, повторите попытку позже."));
                })
                .doOnSuccess(entity -> log.debug("Completed request processing for: [{}]", message));
    }

    @GetMapping(value = "/simple-json/{conversationId}/{message}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity< OrchestraResponse>> simpleChatJson(@PathVariable String message,
                                                                                            @PathVariable String conversationId) {
        log.info("Received simple-json request: /simple-json/{}/{}", conversationId, message);
        return geminiService.generateFormatted(conversationId, message)
                .map(ResponseEntity::ok)
                .onErrorResume(error -> {
                    log.error("Error in simpleChatJson endpoint for message [{}]: {}", message, error.getMessage(), error);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new OrchestraResponse(null, null)));
                });
    }
}
