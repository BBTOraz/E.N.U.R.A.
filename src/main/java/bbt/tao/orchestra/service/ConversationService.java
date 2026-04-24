package bbt.tao.orchestra.service;

import bbt.tao.orchestra.entity.ConversationTitle;
import bbt.tao.orchestra.entity.MessageEntity;
import bbt.tao.orchestra.entity.MessageType;
import bbt.tao.orchestra.observability.OrchestraTelemetry;
import bbt.tao.orchestra.repository.ConversationRepository;
import bbt.tao.orchestra.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private static final Logger log = LoggerFactory.getLogger(ConversationService.class);

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ChatClient chatClient;
    private final OrchestraTelemetry telemetry;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               @Qualifier("plainChatClient") ChatClient chatClient,
                               @Nullable OrchestraTelemetry telemetry) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chatClient = chatClient;
        this.telemetry = telemetry == null ? OrchestraTelemetry.noop() : telemetry;
    }

    public Mono<Void> saveConversationPair(String conversationId, String userMessage, String assistantMessage) {
        if (assistantMessage == null || assistantMessage.isBlank()) {
            log.warn("Skipping save: assistant message is empty for conversation {}", conversationId);
            return Mono.empty();
        }

        return messageRepository.getNextSequenceNumber(conversationId)
                .flatMap(nextSeq -> {
                    LocalDateTime timestamp = LocalDateTime.now();
                    
                    MessageEntity userMsg = new MessageEntity(
                            conversationId,
                            userMessage,
                            MessageType.USER,
                            timestamp,
                            nextSeq
                    );
                    
                    MessageEntity assistantMsg = new MessageEntity(
                            conversationId,
                            assistantMessage,
                            MessageType.ASSISTANT,
                            timestamp.plusNanos(1),
                            nextSeq + 1
                    );

                    return messageRepository.save(userMsg)
                            .then(messageRepository.save(assistantMsg))
                            .then();
                })
                .doOnSuccess(v -> log.info("Saved conversation pair for conversationId={}: USER length={}, ASSISTANT length={}", 
                        conversationId, userMessage.length(), assistantMessage.length()))
                .doOnError(e -> log.error("Failed to save conversation pair for {}: {}", 
                        conversationId, e.getMessage()));
    }

    @Deprecated
    public Mono<MessageEntity> saveMessage(String conversationId, String content, MessageType type) {
        return messageRepository.getNextSequenceNumber(conversationId)
                .flatMap(nextSeq -> {
                    MessageEntity message = new MessageEntity(
                            conversationId,
                            content,
                            type,
                            LocalDateTime.now(),
                            nextSeq
                    );
                    return messageRepository.save(message);
                });
    }

    public Mono<String> generateAndSaveTitle(String conversationId, String firstMessage) {
        return conversationRepository.existsById(conversationId)
                .flatMap(exists -> {
                    if (exists) {
                        return conversationRepository.findById(conversationId)
                                .map(ConversationTitle::title);
                    }
                    return Mono.defer(() -> {
                        long startNanos = System.nanoTime();
                        return Mono.fromCallable(() ->
                                        chatClient.prompt()
                                                .user("Придумай лаконичный заголовок для беседы: " + firstMessage)
                                                .call()
                                                .content()
                                )
                                .subscribeOn(Schedulers.boundedElastic())
                                .flatMap(title ->
                                        conversationRepository.insertTitle(conversationId, title)
                                                .thenReturn(title)
                                )
                                .doOnSuccess(title -> telemetry.recordConversationTitleGeneration(
                                        "success",
                                        elapsedSince(startNanos)
                                ))
                                .doOnError(error -> {
                                    telemetry.recordConversationTitleGeneration(
                                            "error",
                                            elapsedSince(startNanos)
                                    );
                                    telemetry.logApplicationError(error, "conversation_title_generation", conversationId);
                                });
                    });
                });
    }

    public Flux<MessageEntity> getHistory(String conversationId) {
        return messageRepository.findByConversationIdOrdered(conversationId);
    }

    public Mono<ConversationTitle> getConversationTitle(String conversationId) {
        return conversationRepository.findById(conversationId);
    }

    //todo позже нужно изменить на поиск по id пользователя
    public Mono<List<ConversationTitle>> getAllConversationTitles() {
        return conversationRepository.findAll()
                .collectList()
                .map(titles -> titles.stream()
                        .filter(title -> title.title() != null && !title.title().isEmpty())
                        .toList());
    }

    private Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }
}
