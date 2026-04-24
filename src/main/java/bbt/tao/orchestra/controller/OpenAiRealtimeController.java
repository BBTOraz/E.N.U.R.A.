package bbt.tao.orchestra.controller;

import bbt.tao.orchestra.dto.voice.RealtimeSessionResponse;
import bbt.tao.orchestra.service.OpenAiRealtimeSessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/voice/realtime")
public class OpenAiRealtimeController {

    private final OpenAiRealtimeSessionService realtimeSessionService;

    public OpenAiRealtimeController(OpenAiRealtimeSessionService realtimeSessionService) {
        this.realtimeSessionService = realtimeSessionService;
    }

    @PostMapping("/session")
    public Mono<RealtimeSessionResponse> createSession() {
        return realtimeSessionService.createSession();
    }
}
