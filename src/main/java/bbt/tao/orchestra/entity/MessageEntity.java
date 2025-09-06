package bbt.tao.orchestra.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table(name = "spring_ai_chat_memory")
public record MessageEntity(
        @Id
        String conversationId,
        String content,
        String type,
        LocalDateTime timestamp
) { }
