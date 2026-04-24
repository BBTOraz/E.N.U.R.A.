package bbt.tao.orchestra.classifier;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import bbt.tao.orchestra.tools.meta.AgentToolMetadata;
import bbt.tao.orchestra.tools.meta.AgentToolRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class EmbeddingToolClassifier {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingToolClassifier.class);
    private static final String MODEL_NAME = "sentence-transformers/LaBSE";
    private static final float SIMILARITY_THRESHOLD = 0.4f;
    private static final int TOP_N_TOOLS = 2;
    private static final Duration CACHE_TTL = Duration.ofHours(12);

    private final ToolCallbackProvider provider;
    private final AgentToolRegistry registry;
    private final ToolEmbeddingCache embeddingCache;

    private final Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;

    public EmbeddingToolClassifier(ToolCallbackProvider provider,
                                   AgentToolRegistry registry,
                                   ToolEmbeddingCache embeddingCache) {
        this.provider = provider;
        this.registry = registry;
        this.embeddingCache = embeddingCache;
    }

    @PostConstruct
    public void init() {
        ToolCallback[] callbacks = provider.getToolCallbacks();
        if (callbacks.length == 0) {
            log.warn("No ToolCallback beans found.");
            return;
        }

        try {
            loadModel();
        } catch (ModelException | IOException e) {
            log.error("Failed to initialize DJL model.", e);
            return;
        }

        for (ToolCallback callback : callbacks) {
            String toolName = callback.getToolDefinition().name();
            AgentToolMetadata metadata = registry.metadata(toolName);
            if (metadata == null) {
                log.warn("Tool '{}' has no AgentToolMeta metadata and will be skipped.", toolName);
                continue;
            }
            callbacksByName.put(toolName, callback);
            ensureEmbeddingCached(toolName, metadata);
        }

        log.info("Prepared embeddings for {} tool(s).", callbacksByName.size());
    }

    public Set<ToolCallback> classifyTools(String userMessage) {
        if (predictor == null) {
            log.warn("Predictor not ready; returning empty tool list.");
            return Set.of();
        }
        if (callbacksByName.isEmpty()) {
            log.debug("No tool callbacks registered.");
            return Set.of();
        }

        float[] messageEmbedding;
        try {
            messageEmbedding = predictor.predict(userMessage);
        } catch (TranslateException e) {
            log.error("Error embedding user message.", e);
            return Set.of();
        }

        List<Map.Entry<ToolCallback, Float>> scored = new ArrayList<>();
        for (Map.Entry<String, ToolCallback> entry : callbacksByName.entrySet()) {
            String toolName = entry.getKey();
            float[] toolEmbedding = embeddingCache.get(MODEL_NAME, toolName)
                    .orElseGet(() -> computeAndCacheEmbedding(toolName));
            if (toolEmbedding == null) {
                continue;
            }

            float similarity = cosineSimilarity(messageEmbedding, toolEmbedding);
            log.info("Tool: '{}' similarity: {}", toolName, String.format("%.4f", similarity));

            if (similarity >= SIMILARITY_THRESHOLD) {
                scored.add(Map.entry(entry.getValue(), similarity));
            }
        }

        scored.sort((a, b) -> Float.compare(b.getValue(), a.getValue()));
        if (scored.isEmpty()) {
            log.info("No tools above threshold {}.", SIMILARITY_THRESHOLD);
            return Set.of();
        }

        return scored.stream()
                .limit(TOP_N_TOOLS)
                .map(Map.Entry::getKey)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);
    }

    private void loadModel() throws ModelException, IOException {
        if (predictor != null) {
            return;
        }
        log.info("Loading DJL model: {}", MODEL_NAME);
        Criteria<String, float[]> criteria = Criteria.builder()
                .optApplication(Application.NLP.TEXT_EMBEDDING)
                .setTypes(String.class, float[].class)
                .optModelUrls("djl://ai.djl.huggingface.pytorch/" + MODEL_NAME)
                .optEngine("PyTorch")
                .build();
        model = criteria.loadModel();
        predictor = model.newPredictor();
        log.info("Model {} loaded successfully.", MODEL_NAME);
    }

    private void ensureEmbeddingCached(String toolName, AgentToolMetadata metadata) {
        if (predictor == null) {
            return;
        }
        if (embeddingCache.get(MODEL_NAME, toolName).isPresent()) {
            return;
        }
        computeAndStoreEmbedding(toolName, metadata);
    }

    private float[] computeAndCacheEmbedding(String toolName) {
        AgentToolMetadata metadata = registry.metadata(toolName);
        if (metadata == null) {
            log.warn("Metadata for tool '{}' not found during recompute.", toolName);
            return null;
        }
        return computeAndStoreEmbedding(toolName, metadata);
    }

    private float[] computeAndStoreEmbedding(String toolName, AgentToolMetadata metadata) {
        if (predictor == null) {
            return null;
        }
        try {
            float[] embedding = predictor.predict(buildEmbeddingText(metadata));
            embeddingCache.put(MODEL_NAME, toolName, embedding, CACHE_TTL);
            return embedding;
        } catch (TranslateException e) {
            log.error("Failed to compute embedding for tool '{}'.", toolName, e);
            return null;
        }
    }

    private String buildEmbeddingText(AgentToolMetadata metadata) {
        StringBuilder builder = new StringBuilder(metadata.description() == null ? "" : metadata.description());
        List<String> examples = metadata.examples();
        if (examples != null && !examples.isEmpty()) {
            builder.append("\nПримеры запросов:\n");
            examples.forEach(example -> builder.append("- ").append(example).append('\n'));
        }
        return builder.toString();
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Векторы не должны быть null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("Длины векторов не совпадают: " + a.length + " != " + b.length);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            log.warn("Нормы векторов равны нулю — невозможно вычислить косинусное сходство");
            return 0f;
        }
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    @PreDestroy
    public void destroy() {
        log.info("Destroying EmbeddingToolClassifier...");
        if (predictor != null) {
            predictor.close();
            predictor = null;
            log.info("Predictor closed.");
        }
        if (model != null) {
            model.close();
            model = null;
            log.info("Model closed.");
        }
    }
}
