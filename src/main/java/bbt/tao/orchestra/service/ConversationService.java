package bbt.tao.orchestra.service;

import bbt.tao.orchestra.entity.ConversationTitle;
import bbt.tao.orchestra.entity.MessageEntity;
import bbt.tao.orchestra.repository.ConversationRepository;
import bbt.tao.orchestra.repository.MessageRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ChatClient chatClient;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               @Qualifier("plainChatClient") ChatClient chatClient) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.chatClient = chatClient;
    }

    //ручное сохранения беседы, нужен ли он мне ?
    public Mono<MessageEntity> saveMessage(String conversationId, String content, String type) {
        MessageEntity message = new MessageEntity(conversationId, content, type, LocalDateTime.now());
        return messageRepository.save(message);
    }

    public Mono<String> generateAndSaveTitle(String conversationId, String firstMessage) {
    return conversationRepository.existsById(conversationId)
            .flatMap(exists -> {
                if (exists) {
                    // Запись уже существует, можно обновить или пропустить
                    return conversationRepository.findById(conversationId)
                            .map(ConversationTitle::title);
                } else {
                    // Создаем новую запись
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
                            );
                }
            });
    }

    public Flux<MessageEntity> getHistory(String conversationId) {
        return messageRepository.findAllByConversationIdOrderByTimestampAsc(conversationId);
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
}