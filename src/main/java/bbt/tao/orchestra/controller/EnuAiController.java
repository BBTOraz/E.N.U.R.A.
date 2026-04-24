package bbt.tao.orchestra.controller;

import bbt.tao.orchestra.service.rag.RAGService;
import bbt.tao.orchestra.service.enu.llm.EnuAIService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/ai")
public class EnuAiController {

    private final EnuAIService aiService;
    private final RAGService ragService;

    public EnuAiController(EnuAIService aiService, RAGService ragService) {
        this.aiService = aiService;
        this.ragService = ragService;
    }

    public record ChatRequest(String prompt) {}
    public record ChatResponseDto(String response) {}
    public record RagSearchRequest(String query, Integer maxResults) {}
    public record RagSearchResponseDto(List<Document> documents) {}
    public record RagStatusResponseDto(boolean documentsLoaded, String message) {}

    @PostMapping("/ask")
    public Flux<ChatResponseDto> ask(@RequestBody ChatRequest request) {
        return aiService.ask(request.prompt())
                .map(ChatResponseDto::new);
    }

    @PostMapping("/rag/search")
    public Mono<RagSearchResponseDto> searchRag(@RequestBody RagSearchRequest request) {
        int maxResults = request.maxResults() != null ? request.maxResults() : 5;
        List<Document> documents = ragService.searchSimilarDocuments(request.query(), maxResults);
        return Mono.just(new RagSearchResponseDto(documents));
    }

    @PostMapping("/rag/reload")
    public Mono<RagStatusResponseDto> reloadRagDocuments() {
        try {
            ragService.indexPdfHierarchical();
            return Mono.just(new RagStatusResponseDto(true, "Документы успешно перезагружены в RAG"));
        } catch (Exception e) {
            return Mono.just(new RagStatusResponseDto(false, "Ошибка при перезагрузке документов: " + e.getMessage()));
        }
    }

    @GetMapping("/rag/status")
    public Mono<RagStatusResponseDto> getRagStatus() {
        boolean loaded = ragService.isDocumentsLoaded();
        String message = loaded ? "Документы загружены" : "Документы не загружены";
        return Mono.just(new RagStatusResponseDto(loaded, message));
    }
}
