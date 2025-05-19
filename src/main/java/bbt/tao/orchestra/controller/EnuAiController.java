package bbt.tao.orchestra.controller;


import bbt.tao.orchestra.service.enu.llm.EnuAIService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/ai")
public class EnuAiController {

    private final EnuAIService aiService;

    public EnuAiController(EnuAIService aiService) {
        this.aiService = aiService;
    }

    public record ChatRequest(String prompt) {}

    public record ChatResponseDto(String response) {}

    @PostMapping("/ask")
    public Flux<ChatResponseDto> ask(@RequestBody ChatRequest request) {
        return aiService.ask(request.prompt())
                .map(ChatResponseDto::new);
    }
}
