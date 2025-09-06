package bbt.tao.orchestra.repository;

import bbt.tao.orchestra.entity.MessageEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface MessageRepository extends ReactiveCrudRepository<MessageEntity, String> {
    Flux<MessageEntity> findAllByConversationIdOrderByTimestampAsc(String conversationId);
}
