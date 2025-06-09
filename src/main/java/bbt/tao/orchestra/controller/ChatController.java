package bbt.tao.orchestra.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.util.json.schema.SpringAiSchemaModule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
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
        log.info("Received stream request: /stream/{}", message);
        return geminiService.generateStreaming(conversationId, message);
    }

    @GetMapping("/simple/{message}")
    public Mono<ChatResponse> simpleChat(@PathVariable String message) {
        log.info("Received simple request: /simple/{}", message);
        return geminiService.generate(message)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(content -> log.info("Successfully received simple response for message: {}", content.getMetadata()))
                .doOnError(error -> log.error("Error in simpleChat endpoint for message [{}]:", message, error));
    }
}