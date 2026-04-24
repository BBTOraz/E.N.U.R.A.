package bbt.tao.orchestra.aop;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class OllamaApiLoggingAspect {

/*    private final ObjectMapper mapper;

    public OllamaApiLoggingAspect(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    // Перехватываем синхронные chat(...)
    @Pointcut("execution(* org.springframework.ai.ollama.api.OllamaApi.chat(..)) && args(chatRequest)")
    public void chatCall(OllamaApi.ChatRequest chatRequest) {}

    @Before("chatCall(chatRequest)")
    public void logChatJson(OllamaApi.ChatRequest chatRequest) {
        try {
            String json = mapper.writeValueAsString(chatRequest);
            log.info("[OLLAMA-REQUEST] JSON -> {}", json);
        } catch (Exception e) {
            log.warn("Failed to serialize OllamaApi.ChatRequest", e);
        }
    }

    // Перехватываем стриминг streamingChat(...)
    @Pointcut("execution(* org.springframework.ai.ollama.api.OllamaApi.streamingChat(..)) && args(chatRequest)")
    public void streamingChatCall(OllamaApi.ChatRequest chatRequest) {}

    @Before("streamingChatCall(chatRequest)")
    public void logStreamingChatJson(OllamaApi.ChatRequest chatRequest) {
        try {
            String json = mapper.writeValueAsString(chatRequest);
            log.info("[OLLAMA-STREAM-REQUEST] JSON -> {}", json);
        } catch (Exception e) {
            log.warn("Failed to serialize OllamaApi.ChatRequest", e);
        }
    }*/
}
