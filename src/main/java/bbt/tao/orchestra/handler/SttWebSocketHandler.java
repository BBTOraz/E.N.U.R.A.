package bbt.tao.orchestra.handler;

import bbt.tao.orchestra.dto.syntez.SttInputDto; // Замените на ваш пакет
import bbt.tao.orchestra.service.OrchestrationService; // Замените на ваш пакет
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

@Component
public class SttWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SttWebSocketHandler.class);
    private final OrchestrationService orchestrationService;
    private final ObjectMapper objectMapper;

    public SttWebSocketHandler(OrchestrationService orchestrationService, ObjectMapper objectMapper) {
        this.orchestrationService = orchestrationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        log.info("WebSocket connection established from STT: {}", session.getId());

        return session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> {
                    try {
                        SttInputDto dto = objectMapper.readValue(payload, SttInputDto.class);
                        log.info("Received from STT [{}]: '{}'", session.getId(), dto.getText());
                        return orchestrationService.orchestrate(dto.getText())
                                .doOnError(e -> log.error("Orchestration failed for STT input: {}", dto.getText(), e))
                                .onErrorResume(e -> Mono.empty()); // Игнорируем ошибку, чтобы WS не закрылся
                    } catch (Exception e) {
                        log.error("Failed to parse WebSocket message: {}", payload, e);
                        return Mono.empty(); // Игнорируем некорректные сообщения
                    }
                })
                .doOnTerminate(() -> log.info("WebSocket connection terminated: {}", session.getId()))
                .then(); // Возвращаем Mono<Void>
    }
}