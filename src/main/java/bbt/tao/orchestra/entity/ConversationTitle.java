package bbt.tao.orchestra.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.PersistenceCreator;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "conversation_titles")
public record ConversationTitle(
        @Id
        String conversationId,
        String title
) {
}
