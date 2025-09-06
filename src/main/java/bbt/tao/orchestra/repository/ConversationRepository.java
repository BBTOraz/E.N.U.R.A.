package bbt.tao.orchestra.repository;

import bbt.tao.orchestra.entity.ConversationTitle;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

import java.util.List;

public interface ConversationRepository extends ReactiveCrudRepository<ConversationTitle, String> {
    Mono<Boolean> existsByConversationId(String conversationId);

    @Query("INSERT INTO conversation_titles (conversation_id, title) VALUES (:id, :title)")
    Mono<Void> insertTitle(String id, String title);

}