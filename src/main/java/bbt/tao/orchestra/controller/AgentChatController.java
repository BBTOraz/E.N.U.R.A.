package bbt.tao.orchestra.controller;

import bbt.tao.orchestra.agent.AgentOrchestrator;
import bbt.tao.orchestra.dto.api.OrchestraResponse;
import bbt.tao.orchestra.service.format.Summarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private final AgentOrchestrator orchestrator;
    private final bbt.tao.orchestra.service.format.Summarizer summarizer;

    public AgentChatController(AgentOrchestrator orchestrator,
                               Summarizer summarizer) {
        this.orchestrator = orchestrator;
        this.summarizer = summarizer;
    }

    @PostMapping(value = "/{conversationId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> chat(@PathVariable String conversationId,
                                             @RequestBody Map<String, Object> body) {
        try {
            Object m = body.get("message");
            if (m == null) {
                return Mono.just(ResponseEntity.badRequest().body("Поле 'message' обязательно"));
            }
            String message = String.valueOf(m);
            log.info("AgentChatController: request convId={}, message='{}'", conversationId, message);
            return orchestrator.run(conversationId, message)
                    .map(ResponseEntity::ok)
                    .onErrorResume(e -> Mono.just(
                            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .contentType(MediaType.TEXT_PLAIN)
                                    .body("Ошибка агентной обработки: " + e.getMessage())
                    ));
        } catch (Exception ex) {
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Ошибка: " + ex.getMessage()));
        }
    }

    @PostMapping(value = "/envelope/{conversationId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<OrchestraResponse>> chatEnvelope(@PathVariable String conversationId,
                                                                                          @RequestBody Map<String, Object> body) {
        Object m = body.get("message");
        if (m == null) {
            return Mono.just(ResponseEntity.badRequest().body(new OrchestraResponse(null, null)));
        }
        String message = String.valueOf(m);
        log.info("AgentChatController: envelope request convId={}, message='{}'", conversationId, message);
        return orchestrator.run(conversationId, message)
                .flatMap(full -> reactor.core.publisher.Mono.fromCallable(() -> summarizer.summarize(full))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .map(summary -> new OrchestraResponse(full, summary))
                )
                .map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new OrchestraResponse(null, null))));
    }
}
