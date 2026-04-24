package bbt.tao.orchestra.entity;

import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table(name = "spring_ai_chat_memory")
public record MessageEntity(
        @Column("conversation_id")
        String conversationId,
        @Column("content")
        String content,
        @Column("type")
        MessageType type,
        @Column("timestamp")
        LocalDateTime timestamp,
        @Column("sequence_number")
        Long sequenceNumber
) {
    public MessageEntity(String conversationId, String content, MessageType type, LocalDateTime timestamp) {
        this(conversationId, content, type, timestamp, null);
    }
}
