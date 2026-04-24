package bbt.tao.orchestra.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletion;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk;
import org.springframework.ai.openai.api.OpenAiApi.ChatCompletionRequest;
import reactor.core.publisher.Flux;

@Slf4j
@Aspect
@Component
public class OpenAiApiLoggingAspect {

    private final ObjectMapper mapper;

    public OpenAiApiLoggingAspect(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // Pointcut for synchronous chatCompletionEntity(..)
    @Pointcut("execution(* org.springframework.ai.openai.api.OpenAiApi.chatCompletionEntity(..)) && args(chatRequest)")
    public void chatEntityCall(ChatCompletionRequest chatRequest) {}

    // Pointcut for streaming chatCompletionStream(..)
    @Pointcut("execution(* org.springframework.ai.openai.api.OpenAiApi.chatCompletionStream(..)) && args(chatRequest)")
    public void chatStreamCall(ChatCompletionRequest chatRequest) {}

    @Before("chatEntityCall(chatRequest)")
    public void logChatEntityRequest(ChatCompletionRequest chatRequest) {
        try {
            String json = mapper.writeValueAsString(chatRequest);
            log.info("[OPENAI-REQUEST] chatCompletionEntity JSON -> {}", json);
        } catch (Exception e) {
            log.warn("Failed to serialize OpenAiApi.ChatCompletionRequest", e);
        }
    }

    @AfterReturning(pointcut = "chatEntityCall(chatRequest)", returning = "response")
    public void logChatEntityResponse(ChatCompletionRequest chatRequest, ResponseEntity<ChatCompletion> response) {
        try {
            String json = mapper.writeValueAsString(response.getBody());
            log.info("[OPENAI-RESPONSE] chatCompletionEntity JSON -> {}", json);
        } catch (Exception e) {
            log.warn("Failed to serialize OpenAiApi.ChatCompletion response body", e);
        }
    }

    @Before("chatStreamCall(chatRequest)")
    public void logChatStreamRequest(ChatCompletionRequest chatRequest) {
        try {
            String json = mapper.writeValueAsString(chatRequest);
            log.info("[OPENAI-STREAM-REQUEST] chatCompletionStream JSON -> {}", json);
        } catch (Exception e) {
            log.warn("Failed to serialize OpenAiApi.ChatCompletionRequest for stream", e);
        }
    }

    @AfterReturning(pointcut = "chatStreamCall(chatRequest)", returning = "flux")
    public void logChatStreamResponse(ChatCompletionRequest chatRequest, Flux<ChatCompletionChunk> flux) {
        log.info("[OPENAI-STREAM-RESPONSE] chatCompletionStream returned Flux<ChatCompletionChunk> -> {}", flux);
    }
}
