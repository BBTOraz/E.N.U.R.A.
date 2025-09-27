package bbt.tao.orchestra.agent;

import bbt.tao.orchestra.classifier.EmbeddingToolClassifier;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Set;

@Service
public class SolverAgent {

    private static final Logger log = LoggerFactory.getLogger(SolverAgent.class);

    private final ChatClient chatClient;
    private final EmbeddingToolClassifier classifier;
    private final ToolCallbackProvider provider;

    public SolverAgent(@Qualifier("solverAgentChatClient") ChatClient chatClient,
                       EmbeddingToolClassifier classifier, ToolCallbackProvider provider) {
        this.chatClient = chatClient;
        this.classifier = classifier;
        this.provider = provider;
    }

    public Mono<String> solve(String conversationId, String userMessage) {
        return Mono.fromCallable(() -> {
                    Set<ToolCallback> tools = classifier.classifyTools(userMessage);
                    Message user = new UserMessage(userMessage);
                    Prompt prompt = new Prompt(user);

                    log.info("SolverAgent: tools selected count={} for conv={}", tools.size(), conversationId);

                    return chatClient
                            .prompt(prompt)
                            .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                            .toolCallbacks(provider.getToolCallbacks())
                            .call()
                            .content();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}

