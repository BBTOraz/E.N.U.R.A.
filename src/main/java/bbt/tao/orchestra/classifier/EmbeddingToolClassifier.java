package bbt.tao.orchestra.classifier;

import ai.djl.Application;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;


import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class EmbeddingToolClassifier {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingToolClassifier.class);
    private static final String MODEL_NAME = "sentence-transformers/LaBSE";
    private static final float SIMILARITY_THRESHOLD = 0.4f;
    private static final int TOP_N_TOOLS = 2;


    private final List<ToolCallback> availableTools;
    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;
    private final Map<ToolCallback, float[]> toolEmbeddings = new HashMap<>();

    public EmbeddingToolClassifier(ToolCallbackProvider provider) {
        this.availableTools = Arrays.stream(provider.getToolCallbacks())
                .collect(Collectors.toList());
    }

    @PostConstruct
    public void init() {
        log.info("Initializing EmbeddingToolClassifier with {} tools...", availableTools.size());
        if (availableTools.isEmpty()) {
            log.warn("No ToolCallback beans found.");
            return;
        }
        try {
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

            for (ToolCallback callback : availableTools) {
                String toolName = callback.getToolDefinition().name();
                String description = callback.getToolDefinition().description();
                String text = toolName + ": " + description;
                try {
                    float[] emb = predictor.predict(text);
                    toolEmbeddings.put(callback, emb);
                    log.debug("Computed embedding for tool: {}", toolName);
                } catch (TranslateException e) {
                    log.error("Failed embedding for tool: {}", toolName, e);
                }
            }
            log.info("Precomputed embeddings for {} tools.", toolEmbeddings.size());
        } catch (ModelException | IOException e) {
            log.error("Failed to initialize DJL model.", e);
        }
    }

    public Set<ToolCallback> classifyTools(String userMessage) {
        log.info("Classifying tools for message: '{}'", userMessage);
        if (predictor == null || toolEmbeddings.isEmpty()) {
            log.warn("Predictor not ready or no embeddings.");
            return Collections.emptySet();
        }
        float[] msgEmb;
        try {
            msgEmb = predictor.predict(userMessage);
        } catch (TranslateException e) {
            log.error("Error embedding user message.", e);
            return Collections.emptySet();
        }

        List<Map.Entry<ToolCallback, Float>> sims = toolEmbeddings.entrySet().stream()
                .map(entry -> {
                    float sim = cosineSimilarity(msgEmb, entry.getValue());
                    log.info("Tool: '{}', similarity: {}", entry.getKey().getToolDefinition().name(), String.format("%.4f", sim));
                    return Map.entry(entry.getKey(), sim);
                })
                .sorted((e1, e2) -> Float.compare(e2.getValue(), e1.getValue()))
                .toList();

        Set<ToolCallback> selected = sims.stream()
                .limit(TOP_N_TOOLS)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (selected.isEmpty()) {
            log.info("No tools above threshold {}.", SIMILARITY_THRESHOLD);
        } else {
            String names = selected.stream()
                    .map(cb -> cb.getToolDefinition().name())
                    .collect(Collectors.joining(", "));
            log.info("Selected top tools: {}", names);
        }
        return selected;
    }

    private float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Векторы не должны быть null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("Векторы должны иметь одинаковую длину: " +
                                              a.length + " != " + b.length);
        }
        double dot = 0, normA = 0, normB = 0;

        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            log.warn("Обнаружен нулевой вектор при вычислении косинусного сходства");
            return 0f;
        }

        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    @PreDestroy
    public void destroy() {
        log.info("Destroying EmbeddingToolClassifier...");
        if (predictor != null) {
            predictor.close();
            log.info("Predictor closed.");
        }
        if (model != null) {
            model.close();
            log.info("Model closed.");
        }
    }
}
