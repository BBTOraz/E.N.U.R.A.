package bbt.tao.orchestra.service.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPooled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Component
public class RagQueryCache {

    private static final Logger log = LoggerFactory.getLogger(RagQueryCache.class);
    private static final String KEY_PREFIX = "orchestra:rag:query:";
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);
    private static final TypeReference<List<CachedDocument>> DOC_LIST_TYPE = new TypeReference<>() {};

    private final JedisPooled jedis;
    private final ObjectMapper objectMapper;

    public RagQueryCache(JedisPooled jedis, ObjectMapper objectMapper) {
        this.jedis = jedis;
        this.objectMapper = objectMapper;
    }

    public List<Document> getOrCompute(String scope, String rawKey, Supplier<List<Document>> loader) {
        String cacheKey = buildKey(scope, rawKey);
        Optional<List<Document>> cached = read(cacheKey);
        if (cached.isPresent()) {
            log.debug("RagQueryCache: hit scope={} hash={}", scope, shorten(cacheKey));
            return List.copyOf(cached.get());
        }
        log.debug("RagQueryCache: miss scope={} hash={}", scope, shorten(cacheKey));
        List<Document> value = loader.get();
        if (value == null) {
            return List.of();
        }
        if (!CollectionUtils.isEmpty(value)) {
            write(cacheKey, value);
        }
        return List.copyOf(value);
    }

    private Optional<List<Document>> read(String cacheKey) {
        String json = jedis.get(cacheKey);
        if (!StringUtils.hasText(json)) {
            return Optional.empty();
        }
        try {
            List<CachedDocument> stored = objectMapper.readValue(json, DOC_LIST_TYPE);
            return Optional.of(stored.stream().map(this::toDocument).toList());
        } catch (Exception ex) {
            log.warn("RagQueryCache: failed to deserialize cache entry for key={} -> {}", shorten(cacheKey), ex.getMessage());
            jedis.del(cacheKey);
            return Optional.empty();
        }
    }

    private void write(String cacheKey, List<Document> documents) {
        try {
            List<CachedDocument> payload = documents.stream()
                    .map(this::fromDocument)
                    .toList();
            String json = objectMapper.writeValueAsString(payload);
            int ttlSeconds = (int) DEFAULT_TTL.getSeconds();
            if (ttlSeconds > 0) {
                jedis.setex(cacheKey, ttlSeconds, json);
            } else {
                jedis.set(cacheKey, json);
            }
        } catch (Exception ex) {
            log.warn("RagQueryCache: failed to serialize cache entry for key={} -> {}", shorten(cacheKey), ex.getMessage());
        }
    }

    private String buildKey(String scope, String rawKey) {
        String hash = sha256(scope + "::" + rawKey);
        return KEY_PREFIX + scope + ":" + hash;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not supported", ex);
        }
    }

    private String shorten(String key) {
        if (key == null || key.length() <= 16) {
            return key;
        }
        return key.substring(Math.max(0, key.length() - 16));
    }

    private CachedDocument fromDocument(Document document) {
        return new CachedDocument(
                document.getId(),
                document.getText(),
                document.getMetadata()
        );
    }

    private Document toDocument(CachedDocument cached) {
        if (cached.id() != null && !cached.id().isBlank()) {
            return new Document(cached.id(), cached.text(), cached.metadata());
        }
        return new Document(cached.text(), cached.metadata());
    }

    private record CachedDocument(String id, String text, Map<String, Object> metadata) {
    }
}
