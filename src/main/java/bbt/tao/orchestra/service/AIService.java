package bbt.tao.orchestra.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class AIService {
    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    private final ChatClient chatClientBuilder;

    private static final String SYSTEM_PROMPT_RU_INFORMAL_CONCISE = """
            /no_think
            Ты ассистент, помогающий пользователю в поиске информации связанный с университетом.
            Ты должен отвечать кратко и по делу, избегая лишних слов и фраз.
            Отвечать только на русском языке или на казахском (если пользователю удобно на казахском).
            """;

    public AIService(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT_RU_INFORMAL_CONCISE)
                .defaultTools("getStaffInfo")
                .build();
        log.info("ChatClient.Builder for AI Service injected.");
    }

    public Flux<String> generateStreaming(String userMessageContent) {
        log.debug("Sending streaming request to LLM: '{}'", userMessageContent);

        return chatClientBuilder.prompt()
                .user(userMessageContent)
                .stream()
                .content();
    }


    public String generate(String userMessageContent) {
        log.debug("Sending non-streaming request to LLM: '{}'", userMessageContent);
        Message userMessage = new UserMessage(userMessageContent);
        Prompt prompt = new Prompt(userMessage);

        return chatClientBuilder.prompt(prompt)
                .call()
                .content();
    }
}