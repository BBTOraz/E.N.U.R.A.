package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.service.rag.HierarchicalDocumentRetriever;
import bbt.tao.orchestra.service.rag.RAGService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.IndexOptions;
import redis.clients.jedis.search.Schema;

import java.util.Map;

@Configuration
public class RagConfig {

    private static final Logger logger = LoggerFactory.getLogger(RagConfig.class);

    @Value("${spring.data.redis.password}")
    private String redisPassword;

    @Value("${spring.ai.vectorstore.redis.index-name}")
    private String redisIndex;

    @Value("${spring.ai.vectorstore.redis.prefix}")
    private String redisPrefix;

    @Bean
    public ParagraphPdfDocumentReader paragraphPdfDocumentReader() {
        logger.info("Создание ParagraphPdfDocumentReader для PDF: enu/Евразийский национальный университет им. Л.Н. Гумилёва (ЕНУ).pdf");
        var resource = new ClassPathResource("enu/Евразийский национальный университет им. Л.Н. Гумилёва (ЕНУ).pdf");
        var cfg = PdfDocumentReaderConfig.builder()
                .withPageTopMargin(0)
                .withPageBottomMargin(0)
                .withReversedParagraphPosition(false)
                .build();
        return new ParagraphPdfDocumentReader(resource, cfg);
    }

    @Bean
    public TokenTextSplitter textSplitter() {
        logger.info("Создание TokenTextSplitter с размером чанка 300 и перекрытием 100");
        return new TokenTextSplitter(400, 60, 20, 2000, true);
    }

    @Bean
    public JedisPooled jedisPooled() {
        logger.info("Создание JedisPooled с аутентификацией Redis");
        return new JedisPooled("localhost", 6379, null, redisPassword);
    }

    @Bean
    public RedisVectorStore vectorStore(JedisPooled jedisPooled, OllamaEmbeddingModel embeddingModel) {
        logger.info("Создание RedisVectorStore с индексом: {} и префиксом: {}", redisIndex, redisPrefix);

        return RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName(redisIndex)
                .prefix(redisPrefix)
                .initializeSchema(true)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("type"),          // parent/leaf
                        RedisVectorStore.MetadataField.tag("node_id"),
                        RedisVectorStore.MetadataField.tag("parent_id"),
                        RedisVectorStore.MetadataField.numeric("level"),
                        RedisVectorStore.MetadataField.numeric("position"),
                        RedisVectorStore.MetadataField.numeric("page_start"),
                        RedisVectorStore.MetadataField.numeric("page_end"),
                        RedisVectorStore.MetadataField.text("title"),
                        RedisVectorStore.MetadataField.text("heading_path")
                )
                .build();
    }

    @Bean
    public TikaDocumentReader tikaDocumentReader() {
        logger.info("Создание TikaDocumentReader для документа: enu/Евразийский национальный университет им. Л.Н. Гумилёва (ЕНУ).pdf");
        ClassPathResource resource = new ClassPathResource("enu/Евразийский национальный университет им. Л.Н. Гумилёва (ЕНУ).pdf");
        return new TikaDocumentReader(resource);
    }
}

