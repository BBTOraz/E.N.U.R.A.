package bbt.tao.orchestra.service;

import bbt.tao.orchestra.classifier.EmbeddingToolClassifier;
import bbt.tao.orchestra.tools.enu.StaffTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import reactor.core.publisher.Mono;

import java.util.Set;

@Service
public class AIService {
    private static final Logger log = LoggerFactory.getLogger(AIService.class);

    private final EmbeddingToolClassifier classifier;
    private final ChatClient chatClientBuilder;


    public AIService(EmbeddingToolClassifier classifier, @Qualifier("chatClient") ChatClient chatClientBuilder1) {
        this.classifier = classifier;
        this.chatClientBuilder = chatClientBuilder1;
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


    public Mono<ChatResponse> generate(String userMessageContent) {
        log.debug("Sending non-streaming request to LLM: '{}'", userMessageContent);
        Message userMessage = new UserMessage(userMessageContent);
        Prompt prompt = new Prompt(userMessage);
        Set<ToolCallback> tools = classifier.classifyTools(userMessageContent);

        return Mono.fromCallable(() ->
                chatClientBuilder.prompt(prompt)
                        .toolCallbacks(tools.toArray(new ToolCallback[0]))
                        .call()
                        .chatResponse()
        );
    }
}