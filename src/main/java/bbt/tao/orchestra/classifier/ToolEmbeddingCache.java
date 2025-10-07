package bbt.tao.orchestra.classifier;

import org.springframework.stereotype.Component;
import redis.clients.jedis.JedisPooled;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

@Component
public class ToolEmbeddingCache {

    private static final String KEY_PREFIX = "orchestra:tool-embeddings:";

    private final JedisPooled jedis;

    public ToolEmbeddingCache(JedisPooled jedis) {
        this.jedis = jedis;
    }

    public Optional<float[]> get(String modelName, String toolName) {
        String value = jedis.hget(key(modelName), toolName);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(decode(value));
    }

    public void put(String modelName, String toolName, float[] embedding, Duration ttl) {
        jedis.hset(key(modelName), toolName, encode(embedding));
        if (!ttl.isZero() && !ttl.isNegative()) {
            jedis.expire(key(modelName), (int) ttl.getSeconds());
        }
    }

    public void evictModel(String modelName) {
        jedis.del(key(modelName));
    }

    private String key(String modelName) {
        return KEY_PREFIX + modelName;
    }

    private String encode(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float v : embedding) {
            buffer.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private float[] decode(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        FloatBuffer floatBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asFloatBuffer();
        float[] result = new float[floatBuffer.remaining()];
        floatBuffer.get(result);
        return result;
    }
}
