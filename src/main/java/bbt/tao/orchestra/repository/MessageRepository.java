package bbt.tao.orchestra.repository;

import bbt.tao.orchestra.entity.MessageEntity;
import bbt.tao.orchestra.entity.MessageType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public class MessageRepository {
    
    private final DatabaseClient databaseClient;
    
    public MessageRepository(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public Flux<MessageEntity> findByConversationIdOrdered(String conversationId) {
        return databaseClient.sql("""
                        SELECT conversation_id, content, type, timestamp, sequence_number 
                        FROM spring_ai_chat_memory 
                        WHERE conversation_id = :conversationId 
                        ORDER BY sequence_number ASC, timestamp ASC
                        """)
                .bind("conversationId", conversationId)
                .map((row, metadata) -> new MessageEntity(
                        row.get("conversation_id", String.class),
                        row.get("content", String.class),
                        MessageType.valueOf(row.get("type", String.class)),
                        row.get("timestamp", LocalDateTime.class),
                        row.get("sequence_number", Long.class)
                ))
                .all();
    }
    
    public Mono<MessageEntity> save(MessageEntity entity) {
        return databaseClient.sql("""
                        INSERT INTO spring_ai_chat_memory (conversation_id, content, type, timestamp, sequence_number)
                        VALUES (:conversationId, :content, :type, :timestamp, :sequenceNumber)
                        """)
                .bind("conversationId", entity.conversationId())
                .bind("content", entity.content())
                .bind("type", entity.type().toString())
                .bind("timestamp", entity.timestamp())
                .bind("sequenceNumber", entity.sequenceNumber())
                .fetch()
                .rowsUpdated()
                .thenReturn(entity);
    }

    public Mono<Long> getNextSequenceNumber(String conversationId) {
        return databaseClient.sql("""
                        SELECT COALESCE(MAX(sequence_number), 0) + 1 as next_seq
                        FROM spring_ai_chat_memory
                        WHERE conversation_id = :conversationId
                        """)
                .bind("conversationId", conversationId)
                .map((row, metadata) -> row.get("next_seq", Long.class))
                .one()
                .defaultIfEmpty(1L);
    }
}
