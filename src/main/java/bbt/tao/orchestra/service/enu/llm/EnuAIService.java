package bbt.tao.orchestra.service.enu.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;

@Slf4j
@Service
public class EnuAIService {
    private final ChatClient chatClient;

    public EnuAIService(@Qualifier("chatClient") ChatClient chatClient1) {
        this.chatClient = chatClient1;
    }

    public Flux<String> ask(String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .stream()
                .content();
    }
}
