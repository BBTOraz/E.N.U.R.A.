package bbt.tao.orchestra.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VerifierAgent {

    private static final Logger log = LoggerFactory.getLogger(VerifierAgent.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("\\n?\\s*(\\{[\\n\\r\\s\\S]*})\\s*\\n?$");

    private final ChatClient chatClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public VerifierAgent(@Qualifier("verifierAgentChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public Mono<VerificationResult> verify(String conversationId, String originalTask, String draftAnswer) {
        return Mono.fromCallable(() -> {
                    String prompt = "Задача пользователя:" + "\n" + originalTask + "\n\n" +
                            "Ответ от ллм на запрос пользователя: " + "\n" + draftAnswer + "\n\n" +
                            "Проверь ответ от ллм и верни ТОЛЬКО JSON строго по схеме. Без объяснений, только JSON.";

                    String content = chatClient
                            .prompt()
                            .user(prompt)
                            .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                            .call()
                            .content();

                    try {
                        return parseResult(content);
                    } catch (Exception ex) {
                        log.warn("VerifierAgent: parse failed, trying to extract JSON block. err={}", ex.getMessage());
                        String extracted = tryExtractJson(content);
                        return parseResult(extracted);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private VerificationResult parseResult(String content) throws Exception {
        JsonNode node = mapper.readTree(content);
        VerificationResult res = mapper.treeToValue(node, VerificationResult.class);
        if (res.getReasons() == null) {
            res.setReasons(java.util.Collections.emptyList());
        }
        if (!res.isOk() && (res.getRequiredChanges() == null || res.getRequiredChanges().isBlank())) {
            res.setRequiredChanges("Уточните требуемые изменения: какие факты, формат или шаги необходимо исправить.");
        }
        return res;
    }

    private String tryExtractJson(String s) {
        if (s == null) return "{}";
        Matcher m = JSON_BLOCK.matcher(s.trim());
        if (m.find()) {
            return m.group(1);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return "{}";
    }
}

