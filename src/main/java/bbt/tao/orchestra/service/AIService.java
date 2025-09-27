package bbt.tao.orchestra.service;

import bbt.tao.orchestra.classifier.EmbeddingToolClassifier;
import bbt.tao.orchestra.dto.api.OrchestraResponse;
import bbt.tao.orchestra.service.format.Summarizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;

@Service
public class AIService {
    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    private final ToolCallbackProvider toolCallbackProvider;
    private final EmbeddingToolClassifier classifier;
    private final ChatClient chatClientBuilder;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Summarizer summarizer;

    public AIService(ToolCallbackProvider toolCallbackProvider,
                     EmbeddingToolClassifier classifier,
                     @Qualifier("chatClient") ChatClient chatClientBuilder1,
                     Summarizer summarizer) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.classifier = classifier;
        this.chatClientBuilder = chatClientBuilder1;
        this.summarizer = summarizer;
    }

    public Flux<String> generateStreaming(String conversationId, String userMessageContent) {
        Set<ToolCallback> tools = classifier.classifyTools(userMessageContent);

        log.debug("Sending streaming request to LLM: '{}'", userMessageContent);
        return chatClientBuilder.prompt()
                .user(userMessageContent)
                .advisors(advisorSpec ->
                        advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .toolCallbacks(tools.toArray(new ToolCallback[0]))
                .stream()
                .content();
    }

    public Mono<String> generate(String conversationId, String userMessageContent) {
        log.debug("Sending non-streaming request to LLM: '{}'", userMessageContent);
        Message userMessage = new UserMessage(userMessageContent);
        Prompt prompt = new Prompt(userMessage);
        return Mono.fromCallable(() ->
                chatClientBuilder.prompt(prompt)
                        .advisors(advisorSpec ->
                                advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                        .toolCallbacks(toolCallbackProvider.getToolCallbacks())
                        .call()
                        .content()
        );
    }

    public Mono<OrchestraResponse> generateFormatted(String conversationId, String userMessageContent) {
        return Mono.fromCallable(() -> {
                    Message userMessage = new UserMessage(userMessageContent);
                    Prompt prompt = new Prompt(userMessage);
                    return chatClientBuilder.prompt(prompt)
                            .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                            .toolCallbacks(toolCallbackProvider.getToolCallbacks())
                            .call()
                            .content();
                })
                .flatMap(full -> Mono.fromCallable(() -> summarizer.summarize(full))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(summary -> new OrchestraResponse(full, summary))
                );
    }

    private String ensureJsonEnvelope(String content) {
        if (content == null) {
            return "{\"schema\":\"orchestra.v1\",\"type\":\"assistant_text\",\"text\":null}";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                objectMapper.readTree(trimmed);
                return trimmed;
            } catch (Exception e) {
                log.warn(e.getMessage());
            }
        }
        Map<String, Object> env = Map.of(
                "schema", "orchestra.v1",
                "type", "assistant_text",
                "text", content
        );
        try {
            return objectMapper.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            return "{\"schema\":\"orchestra.v1\",\"type\":\"assistant_text\",\"text\":\"" + content.replace("\"","\\\"") + "\"}";
        }
    }
}
