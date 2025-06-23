package bbt.tao.orchestra.advisor;

import bbt.tao.orchestra.handler.tool.InlineFunctionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class InlineFunctionRetryAdvisor implements BaseAdvisor {
    private static final Pattern INLINE_FN = Pattern.compile(
            "(?s)<function=([^>{]+)(\\{.*})</function>"
    );

    private final List<InlineFunctionHandler> handlers;

    public InlineFunctionRetryAdvisor(List<InlineFunctionHandler> handlers) {
        this.handlers = handlers;
    }


    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        log.info("InlineFunctionRetryAdvisor.after()");
        assert response.chatResponse() != null;

        String content = response.chatResponse()
                .getResult()
                .getOutput()
                .getText();

        log.info("InlineFunctionRetryAdvisor content: {}", content);

        assert content != null;
        Matcher m = INLINE_FN.matcher(content);
        log.info("InlineFunctionRetryAdvisor matcher: {}", m);
        if (!m.find()) {
            return response;
        }

        String fn   = m.group(1);
        String json = m.group(2).trim();

        InlineFunctionHandler handler = handlers.stream()
                .filter(h -> h.functionName().equals(fn))
                .findFirst()
                .orElse(null);

        if (handler == null) {
            return response;
        }

        try {
            String result = handler.handle(json);
            AssistantMessage assistantMsg = new AssistantMessage(result);
            Generation gen = new Generation(assistantMsg, ChatGenerationMetadata.NULL);
            ChatResponse newChatResp = new ChatResponse(
                    List.of(gen),
                    response.chatResponse().getMetadata()
            );

            return response.mutate()
                    .chatResponse(newChatResp)
                    .build();
        }
        catch (Exception ex) {
            log.error("Failed to handle inline function: {}", fn, ex);
            return response;
        }
    }

    //todo  пока не понял цепочку вызовов, но в идеале нужно
    @Override
    public int getOrder() {
        return 100;
    }
}