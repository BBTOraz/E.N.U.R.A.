package bbt.tao.orchestra.service.rag;

import org.springframework.ai.document.Document;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorator that allows reusing documents placed into the query context
 * to avoid redundant calls to the underlying retriever.
 */
public class PreloadingDocumentRetriever implements DocumentRetriever {

    public static final String CONTEXT_KEY = "preloaded_documents";

    private final DocumentRetriever delegate;
    private static final Logger log = LoggerFactory.getLogger(PreloadingDocumentRetriever.class);

    public PreloadingDocumentRetriever(DocumentRetriever delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<Document> retrieve(Query query) {
        Map<String, Object> context = query.context();
        Object fromContext = context.get(CONTEXT_KEY);
        if (fromContext instanceof List<?> list && list.stream().allMatch(Document.class::isInstance)) {
            @SuppressWarnings("unchecked")
            List<Document> documents = (List<Document>) list;
            log.debug("PreloadingDocumentRetriever: cache hit with {} document(s)", documents.size());
            return documents;
        }
        log.debug("PreloadingDocumentRetriever: cache miss, delegating retrieval");
        return delegate.retrieve(query);
    }
}
