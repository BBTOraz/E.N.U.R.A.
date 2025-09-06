package bbt.tao.orchestra.service.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class HierarchicalDocumentRetriever implements DocumentRetriever {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalDocumentRetriever.class);

    private final RAGService ragService;

    private final int defaultTopKLeaf;
    private final int defaultMaxFamilies;
    private final int defaultMaxChildrenPerFamily;

    @Autowired
    public HierarchicalDocumentRetriever(RAGService ragService) {
        this(ragService, 12, 2, 3);
    }

    public HierarchicalDocumentRetriever(RAGService ragService,
                                         int defaultTopKLeaf,
                                         int defaultMaxFamilies,
                                         int defaultMaxChildrenPerFamily) {
        this.ragService = ragService;
        this.defaultTopKLeaf = defaultTopKLeaf;
        this.defaultMaxFamilies = defaultMaxFamilies;
        this.defaultMaxChildrenPerFamily = defaultMaxChildrenPerFamily;
    }

    @Override
    public List<Document> retrieve(Query query) {
        final String q = query.text();
        final Map<String, Object> ctx = query.context();

        int topKLeaf = coerceInt(ctx.get("rag.topKLeaf"), defaultTopKLeaf);
        int maxFamilies = coerceInt(ctx.get("rag.maxFamilies"), defaultMaxFamilies);
        int maxChildrenPerFamily = coerceInt(ctx.get("rag.maxChildrenPerFamily"), defaultMaxChildrenPerFamily);

        log.debug("HierarchicalDocumentRetriever.retrieve(): q='{}', topKLeaf={}, maxFamilies={}, maxChildrenPerFamily={}",
                q, topKLeaf, maxFamilies, maxChildrenPerFamily);

        return ragService.searchHierarchical(q, topKLeaf, maxFamilies, maxChildrenPerFamily);
    }

    private static int coerceInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }
}


