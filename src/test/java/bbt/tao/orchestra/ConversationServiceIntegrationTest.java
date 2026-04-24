package bbt.tao.orchestra;

import bbt.tao.orchestra.entity.MessageEntity;
import bbt.tao.orchestra.entity.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.r2dbc.DataR2dbcTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.ActiveProfiles;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataR2dbcTest
@ActiveProfiles("test")
class ConversationServiceIntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void clean() {
        // Spring AI creates the table automatically via spring.ai.chat.memory.repository.jdbc.initialize-schema=always
        // Just clean the data for each test
        databaseClient.sql("DELETE FROM spring_ai_chat_memory").fetch().rowsUpdated().block();
    }

    @Test
    void loadsMessagesFromSpringAiChatMemory() {
        String conversationId = "conv-" + System.nanoTime();

        databaseClient.sql("""
                        INSERT INTO spring_ai_chat_memory (conversation_id, content, type, timestamp)
                        VALUES (:conversationId, :content, :type, :timestamp)
                        """)
                .bind("conversationId", conversationId)
                .bind("content", "integration message")
                .bind("type", MessageType.USER.toString())
                .bind("timestamp", LocalDateTime.now())
                .fetch()
                .rowsUpdated()
                .block();

        StepVerifier.create(
                        databaseClient.sql("""
                                        SELECT conversation_id, content, type, timestamp 
                                        FROM spring_ai_chat_memory 
                                        WHERE conversation_id = :conversationId 
                                        ORDER BY timestamp ASC
                                        """)
                                .bind("conversationId", conversationId)
                                .map((row, metadata) -> new MessageEntity(
                                        row.get("conversation_id", String.class),
                                        row.get("content", String.class),
                                        MessageType.valueOf(row.get("type", String.class)),
                                        row.get("timestamp", LocalDateTime.class)
                                ))
                                .all()
                )
                .assertNext(entity -> {
                    assertThat(entity.conversationId()).isEqualTo(conversationId);
                    assertThat(entity.content()).isEqualTo("integration message");
                    assertThat(entity.type()).isEqualTo(MessageType.USER);
                })
                .verifyComplete();
    }
}
