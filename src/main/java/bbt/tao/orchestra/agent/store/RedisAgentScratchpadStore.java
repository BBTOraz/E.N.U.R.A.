package bbt.tao.orchestra.agent.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class RedisAgentScratchpadStore implements AgentScratchpadStore {

    private static final String KEY_PREFIX = "orchestra:scratchpad:";
    private static final String FIELD_DOCUMENTS = "documents";
    private static final String FIELD_DRAFT = "draft";
    private static final String FIELD_TOOL = "tool";
    private static final Duration TTL = Duration.ofHours(1);

    private static final TypeReference<List<StoredDocument>> DOCUMENT_LIST_TYPE = new TypeReference<>() {};

    private final JedisPooled jedis;
    private final ObjectMapper objectMapper;

    public RedisAgentScratchpadStore(JedisPooled jedis, ObjectMapper objectMapper) {
        this.jedis = jedis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveDocuments(String conversationId, List<Document> documents) {
        String key = key(conversationId);
        if (CollectionUtils.isEmpty(documents)) {
            jedis.hdel(key, FIELD_DOCUMENTS);
            touchTtl(key);
            return;
        }
        List<StoredDocument> payload = documents.stream()
                .map(this::toStored)
                .toList();
        writeJsonField(key, FIELD_DOCUMENTS, payload);
    }

    @Override
    public List<Document> loadDocuments(String conversationId) {
        String json = jedis.hget(key(conversationId), FIELD_DOCUMENTS);
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<StoredDocument> stored = objectMapper.readValue(json, DOCUMENT_LIST_TYPE);
            return stored.stream().map(this::fromStored).toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось прочитать документы из Redis", ex);
        }
    }

    @Override
    public void saveDraft(String conversationId, String draft) {
        String key = key(conversationId);
        if (draft == null) {
            jedis.hdel(key, FIELD_DRAFT);
        } else {
            jedis.hset(key, FIELD_DRAFT, draft);
        }
        touchTtl(key);
    }

    @Override
    public Optional<String> loadDraft(String conversationId) {
        String value = jedis.hget(key(conversationId), FIELD_DRAFT);
        return Optional.ofNullable(value);
    }

    @Override
    public void saveSelectedTool(String conversationId, String toolName) {
        String key = key(conversationId);
        if (!StringUtils.hasText(toolName)) {
            jedis.hdel(key, FIELD_TOOL);
        } else {
            jedis.hset(key, FIELD_TOOL, toolName);
        }
        touchTtl(key);
    }

    @Override
    public Optional<String> loadSelectedTool(String conversationId) {
        return Optional.ofNullable(jedis.hget(key(conversationId), FIELD_TOOL));
    }

    @Override
    public void clear(String conversationId) {
        jedis.del(key(conversationId));
    }

    private void writeJsonField(String key, String field, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            jedis.hset(key, field, json);
            touchTtl(key);
        } catch (Exception ex) {
            throw new IllegalStateException("Не удалось сохранить данные в Redis", ex);
        }
    }

    private void touchTtl(String key) {
        if (TTL.isZero() || TTL.isNegative()) {
            return;
        }
        jedis.expire(key, (int) TTL.getSeconds());
    }

    private StoredDocument toStored(Document document) {
        return new StoredDocument(
                document.getId(),
                document.getText(),
                document.getMetadata() == null ? Collections.emptyMap() : document.getMetadata()
        );
    }

    private Document fromStored(StoredDocument stored) {
        Map<String, Object> metadata = stored.metadata() == null ? Collections.emptyMap() : stored.metadata();
        if (StringUtils.hasText(stored.id())) {
            return new Document(stored.id(), defaultText(stored.text()), metadata);
        }
        return new Document(defaultText(stored.text()), metadata);
    }

    private String defaultText(String text) {
        return text == null ? "" : text;
    }

    private String key(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private record StoredDocument(String id, String text, Map<String, Object> metadata) {
    }
}
