package bbt.tao.orchestra.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import redis.clients.jedis.JedisPooled;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Иерархический RAG:
 *  - ETL: ParagraphPdfDocumentReader → сбор секций (заголовки+body), parent/leaf
 *  - Поиск: 2 прогона (PRF → RRF), дедуп/диверсификация, семейный скор
 *  - Устойчивость: гидратация метаданных из Redis JSON; lazy-resolve parent
 */
@Service
public class RAGService {

    private static final Logger log = LoggerFactory.getLogger(RAGService.class);

    // Метаданные
    public static final String MD_NODE_ID      = "node_id";
    public static final String MD_PARENT_ID    = "parent_id";
    public static final String MD_LEVEL        = "level";
    public static final String MD_TITLE        = "title";
    public static final String MD_HEADING_PATH = "heading_path";
    public static final String MD_PAGE_START   = "page_start";
    public static final String MD_PAGE_END     = "page_end";
    public static final String MD_POSITION     = "position";
    public static final String MD_TYPE         = "type";
    public static final String TYPE_PARENT     = "parent";
    public static final String TYPE_LEAF       = "leaf";

    // Для bge/gte полезно префиксовать запрос
    private static final String EMBED_QUERY_PREFIX = "query: ";

    private final VectorStore vectorStore;
    private final ParagraphPdfDocumentReader paragraphPdfDocumentReader;
    private final TikaDocumentReader tikaDocumentReader;
    private final TokenTextSplitter textSplitter;
    private final JedisPooled jedis;
    private final ObjectMapper mapper = new ObjectMapper();

    // кэш родителей (node_id → parent Document)
    private final Map<String, Document> parentById = new ConcurrentHashMap<>();
    private volatile boolean documentsLoaded = false;

    public RAGService(VectorStore vectorStore,
                      ParagraphPdfDocumentReader paragraphPdfDocumentReader,
                      TikaDocumentReader tikaDocumentReader,
                      TokenTextSplitter textSplitter,
                      JedisPooled jedis) {
        this.vectorStore = vectorStore;
        this.paragraphPdfDocumentReader = paragraphPdfDocumentReader;
        this.tikaDocumentReader = tikaDocumentReader;
        this.textSplitter = textSplitter;
        this.jedis = jedis;
    }

    // ========================= И Н Д Е К С А Ц И Я =========================

    /** Индексация с корректной иерархией: только заголовки → секции; тело секции из body-параграфов между заголовками. */
    public void indexPdfHierarchical() {
        log.info("Иерархическая индексация PDF…");
        List<Document> paras = paragraphPdfDocumentReader.get();
        log.info("Параграфов из PDF: {}", paras.size());

        List<Section> sections = assembleSections(paras);

        Map<String, Long> childCount = sections.stream()
                .filter(s -> s.parentId != null)
                .collect(Collectors.groupingBy(s -> s.parentId, Collectors.counting()));

        List<Document> toIndex = new ArrayList<>();
        parentById.clear();

        for (Section s : sections) {
            boolean isLeaf = childCount.getOrDefault(s.nodeId, 0L) == 0L;

            // index: parent (контейнер секции с локальным контентом)
            Document parentDoc = toDoc(s, TYPE_PARENT, s.assembledBodyText);
            parentById.put(s.nodeId, parentDoc);
            toIndex.add(parentDoc);

            // index: leaf parts только для листов
            if (isLeaf && s.assembledBodyText != null && s.assembledBodyText.strip().length() > 20) {
                toIndex.addAll(splitLeaf(parentDoc, s.assembledBodyText));
            }
        }

        long parents = toIndex.stream().filter(d -> TYPE_PARENT.equals(d.getMetadata().get(MD_TYPE))).count();
        long leaves  = toIndex.stream().filter(d -> TYPE_LEAF.equals(d.getMetadata().get(MD_TYPE))).count();
        log.info("Индексируем: parents={}, leaves(parts)={}", parents, leaves);

        vectorStore.add(toIndex);
        long leavesNoPid = toIndex.stream()
                .filter(d -> TYPE_LEAF.equals(d.getMetadata().get(MD_TYPE)))
                .filter(d -> d.getMetadata().get(MD_PARENT_ID) == null)
                .count();
        log.info("Проверка индексации: листьев без parent_id = {}", leavesNoPid);
        printTree(sections, 120);

        documentsLoaded = true;
        log.info("Иерархическая индексация завершена.");
    }

    /** Плоская индексация через Tika (fallback/отладка). */
    public void indexWithTikaFallback() {
        log.info("Fallback Tika ETL (плоский).");
        List<Document> documents = tikaDocumentReader.get();
        List<Document> chunks = textSplitter.apply(documents);
        vectorStore.add(chunks);
        documentsLoaded = true;
        log.info("Индексировано чанков: {}", chunks.size());
    }

    // ============================== П О И С К ==============================

    /**
     * Иерархический поиск с PRF+RRF, дедуп/диверсификацией и семейным скором.
     */
    public List<Document> searchHierarchical(String query,
                                             int topKLeaf,
                                             int maxFamilies,
                                             int maxChildrenPerFamily) {
        log.info("Иерархический поиск: q='{}' | kLeaf={} | families<= {} | children/family<= {}",
                query, topKLeaf, maxFamilies, maxChildrenPerFamily);

        final String q0 = query == null ? "" : query.trim();
        final String qEmbed = EMBED_QUERY_PREFIX + q0;

        // pass#1: листья
        List<Document> raw1 = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(qEmbed)
                        .topK(Math.max(topKLeaf * 3, 30))
                        .filterExpression("type == 'leaf'")
                        .build()
        );
        List<Document> hyd1 = raw1.stream().map(this::hydrateFromRedis).toList();
        log.info("vectorStore pass#1: {}", hyd1.size());
        log.info("metadata samples: {}", hyd1.stream().map(d -> d.getMetadata().toString()).collect(Collectors.toList()));
        debugTypeStats(hyd1);

        // PRF → q2
        String prfTerms = expandQueryPRF(hyd1, 10, 8);
        final String q2 = prfTerms.isBlank() ? qEmbed : (EMBED_QUERY_PREFIX + q0 + " " + prfTerms);

        // pass#2: листья
        List<Document> raw2 = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query(q2)
                        .topK(Math.max(topKLeaf * 3, 30))
                        .filterExpression("type == 'leaf'")
                        .build()
        );
        List<Document> hyd2 = raw2.stream().map(this::hydrateFromRedis).toList();
        log.info("vectorStore pass#2 (PRF): {}", hyd2.size());
        log.info("metadata samples: {}", hyd2.stream().map(d -> d.getMetadata().toString()).collect(Collectors.toList()));
        debugTypeStats(hyd2);

        // RRF fuse
        List<Document> fused = rrfFuse(hyd1, hyd2, 60);

        // dedup по base node + диверсификация (Jaccard)
        Set<String> seenNodes = new HashSet<>();
        List<Document> deduped = new ArrayList<>();
        for (Document d : fused) {
            if (!isLeaf(d)) continue;
            String nid = baseNodeId((String) d.getMetadata().get(MD_NODE_ID));
            if (nid != null && seenNodes.add(nid)) deduped.add(d);
        }

        List<Document> diversified = new ArrayList<>();
        for (Document d : deduped) {
            String sig = (d.getMetadata().get(MD_TITLE)) + " " + preview(d.getText(), 400);
            Set<String> cur = tokens(sig);
            boolean tooSimilar = diversified.stream().anyMatch(prev -> {
                String prevSig = (prev.getMetadata().get(MD_TITLE)) + " " + preview(prev.getText(), 400);
                return jaccard(cur, tokens(prevSig)) >= 0.6;
            });
            if (!tooSimilar) diversified.add(d);
        }

        List<Document> leafHits = diversified.stream().limit(topKLeaf).toList();

        // семьи
        Map<String, List<Document>> byParent = new LinkedHashMap<>();
        for (Document d : leafHits) {
            String pid = (String) d.getMetadata().get(MD_PARENT_ID);
            if (pid != null) byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(d);
        }

        record Family(String parentId, Document parent, List<Document> children, double score) {}
        Set<String> qTok = tokens(q0);

        List<Family> families = byParent.entrySet().stream()
                .map(e -> {
                    String pid = e.getKey();
                    Document parent = resolveParent(pid);
                    List<Document> kids = e.getValue().stream()
                            .sorted(Comparator.comparingInt(d -> intOr(d.getMetadata(), 0, MD_POSITION)))
                            .limit(maxChildrenPerFamily)
                            .toList();

                    double bestSem = kids.stream()
                            .mapToDouble(d -> 1.0 - Math.min(1.0, doubleOr(d.getMetadata().get("distance"), 1.0)))
                            .max().orElse(0.0);

                    double lexOverlap = 0.0;
                    if (parent != null) {
                        Set<String> pTok = tokens(String.valueOf(parent.getMetadata().get(MD_TITLE)) + " " +
                                String.valueOf(parent.getMetadata().get(MD_HEADING_PATH)));
                        lexOverlap = jaccard(qTok, pTok);
                    }

                    double penalty = duplicatePenalty(kids);
                    double score = 0.7 * bestSem + 0.3 * lexOverlap - penalty;

                    return new Family(pid, parent, kids, score);
                })
                .sorted(Comparator.comparingDouble((Family f) -> f.score).reversed())
                .limit(maxFamilies)
                .toList();

        // итоговый контекст: parent → children
        List<Document> finalCtx = new ArrayList<>();
        for (Family f : families) {
            if (f.parent != null) finalCtx.add(f.parent);
            finalCtx.addAll(f.children);
        }

        log.info("Выбрано семей: {} | Итоговых документов: {}", families.size(), finalCtx.size());
        for (int i = 0; i < finalCtx.size(); i++) {
            Document d = finalCtx.get(i);
            log.info("#{} [{}] {} — {} [pages {}-{}]",
                    i + 1,
                    d.getMetadata().get(MD_TYPE),
                    d.getMetadata().get(MD_TITLE),
                    d.getMetadata().get(MD_HEADING_PATH),
                    d.getMetadata().get(MD_PAGE_START),
                    d.getMetadata().get(MD_PAGE_END));
        }
        return finalCtx;
    }

    /** Плоский поиск (для сравнения/отладки). */
    public List<Document> searchSimilarDocuments(String query, int k) {
        log.info("Плоский поиск: q='{}', k={}", query, k);
        return vectorStore.similaritySearch(SearchRequest.builder().query(query).topK(k).build());
    }

    public boolean isDocumentsLoaded() { return documentsLoaded; }

    // ====================== Д В У Х Ф А З Н А Я  С Б О Р К А ======================

    private static class Item {
        int level; String title; String text;
        Integer pageStart; Integer pageEnd; Integer position;
        Map<String,Object> md;
    }

    private static class Section {
        String nodeId; String parentId; int level;
        String title; String headingPath;
        Integer pageStart; Integer pageEnd; Integer position;
        String assembledBodyText;
    }

    /** выделяем заголовки и приклеиваем body между текущим и следующим заголовком того же/меньшего уровня */
    private List<Section> assembleSections(List<Document> paras) {
        List<Item> items = new ArrayList<>();
        int posCounter = 0;
        for (Document d : paras) {
            Map<String,Object> md = d.getMetadata();
            Item it = new Item();
            it.level = intOr(md, 0, "level", "paragraph_level", "toc_level");
            it.title = strOr(md, "title", "heading", "paragraph_title");
            it.text  = d.getText();
            it.pageStart = intOrNull(md, "page_number", "start_page_number", "page");
            it.pageEnd   = intOrNull(md, "end_page_number", "endPageNumber");
            it.position = ++posCounter;
            it.md = md;
            items.add(it);
        }

        List<Item> headings = items.stream()
                .filter(it -> it.level > 0 && it.title != null && !it.title.isBlank())
                .sorted(
                        Comparator
                                .comparing((Item it) -> it.pageStart == null ? Integer.MAX_VALUE : it.pageStart)
                                .thenComparingInt(it -> it.level)     // родитель раньше ребёнка на той же странице
                                .thenComparingInt(it -> it.position)  // наш стабильный входной порядок
                )
                .toList();

        Deque<Section> stack = new ArrayDeque<>();
        List<Section> sections = new ArrayList<>();
        for (Item h : headings) {
            while (!stack.isEmpty() && stack.peek().level >= h.level) {
                stack.pop();
            }
            List<String> path = stack.stream().map(s -> s.title).collect(Collectors.toCollection(ArrayList::new));
            path.add(h.title);
            String headingPath = String.join(" > ", path);

            Section s = new Section();
            s.nodeId = UUID.randomUUID().toString();
            s.parentId = stack.peek() != null ? stack.peek().nodeId : null;
            s.level = h.level;
            s.title = h.title;
            s.headingPath = headingPath;
            s.pageStart = h.pageStart;
            s.pageEnd = h.pageEnd;
            s.position = h.position;

            sections.add(s);
            stack.push(s);
        }

        // собрать body (level==0) между cur.position и позицией следующего заголовка с level<=cur.level
        for (int i = 0; i < sections.size(); i++) {
            Section cur = sections.get(i);
            int curPos = cur.position;
            int curLevel = cur.level;

            int endPosExclusive = Integer.MAX_VALUE;
            for (int j = i + 1; j < sections.size(); j++) {
                Section nx = sections.get(j);
                if (nx.level <= curLevel) { endPosExclusive = nx.position; break; }
            }

            StringBuilder body = new StringBuilder();
            for (Item it : items) {
                if (it.level == 0 && it.position != null && it.position > curPos && it.position < endPosExclusive) {
                    String t = safeText(it.text).strip();
                    if (!t.isBlank()) {
                        if (!body.isEmpty()) body.append("\n");
                        body.append(t);
                    }
                }
            }
            if (body.isEmpty()) {
                String tail = safeText(findNonHeadingTail(items, curPos, endPosExclusive)).strip();
                if (!tail.isBlank()) body.append(tail);
            }
            cur.assembledBodyText = body.toString();
        }

        return sections;
    }

    private String findNonHeadingTail(List<Item> items, int curPos, int endPosExclusive) {
        Item head = items.stream().filter(it -> it.position == curPos).findFirst().orElse(null);
        if (head == null || head.text == null) return "";
        String[] lines = head.text.split("\\R+");
        StringBuilder sb = new StringBuilder();
        boolean tail = false;
        for (String ln : lines) {
            String s = ln.strip();
            if (!tail) {
                if (s.endsWith(".") || s.endsWith(":") || s.isEmpty()) tail = true;
                continue;
            }
            if (looksLikeHeading(s)) break;
            if (!s.isBlank()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private boolean looksLikeHeading(String s) {
        if (s.length() >= 200) return false;
        String up = s.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim();
        int caps = 0, words = 0;
        for (String w : up.split("\\s+")) {
            if (w.isBlank()) continue; words++;
            if (w.length() >= 3 && w.equals(w.toUpperCase(Locale.ROOT))) caps++;
        }
        return words > 0 && caps >= Math.max(1, Math.round(words * 0.6));
    }

    // =========================== У Т И Л И Т Ы ===========================

    private Document toDoc(Section s, String type, String overrideText) {
        Map<String, Object> md = new LinkedHashMap<>();
        md.put(MD_NODE_ID, s.nodeId);
        if (s.parentId != null) md.put(MD_PARENT_ID, s.parentId);
        md.put(MD_LEVEL, s.level);
        md.put(MD_TITLE, s.title);
        md.put(MD_HEADING_PATH, s.headingPath);
        if (s.pageStart != null) md.put(MD_PAGE_START, s.pageStart);
        if (s.pageEnd != null) md.put(MD_PAGE_END, s.pageEnd);
        if (s.position != null) md.put(MD_POSITION, s.position);
        md.put(MD_TYPE, type);
        return new Document(overrideText != null ? overrideText : "", md);
    }

    private List<Document> splitLeaf(Document parentDoc, String fullText) {
        // базовые метаданные секции
        Map<String, Object> base = new LinkedHashMap<>(parentDoc.getMetadata());

        // id самой секции (а не её родителя)
        String sectionId = String.valueOf(base.get(MD_NODE_ID));

        // ВАЖНО: листы должны ссылаться на свою секцию
        base.put(MD_PARENT_ID, sectionId);
        base.put(MD_TYPE, TYPE_LEAF);

        Document leafBase = new Document(fullText, base);

        List<Document> parts = textSplitter.apply(List.of(leafBase));
        for (int i = 0; i < parts.size(); i++) {
            Document p = parts.get(i);
            // node_id = sectionId#k
            p.getMetadata().put(MD_NODE_ID, sectionId + "#" + (i + 1));
            // parent_id уже = sectionId (см. выше)
        }
        return parts;
    }

    private void printTree(List<Section> sections, int previewChars) {
        log.info("=== Иерархия секций ({} шт.) ===", sections.size());
        for (Section s : sections) {
            String indent = "  ".repeat(Math.max(0, s.level));
            String pages = (s.pageStart != null)
                    ? (s.pageEnd != null ? String.format("[%d,%d]", s.pageStart, s.pageEnd)
                    : String.format("[%d,-1]", s.pageStart))
                    : "[-,-]";
            log.info("{}• {} {} pos={}", indent, s.title, pages, s.position);
            if (s.assembledBodyText != null && !s.assembledBodyText.isBlank()) {
                log.debug("{}  └ {}", indent, preview(s.assembledBodyText, previewChars).replaceAll("\\s+", " "));
            }
        }
        log.info("=== Конец иерархии ===");
    }

    // --- Redis JSON «гидратация» и parent-резолв ---

    private Document hydrateFromRedis(Document d) {
        Map<String, Object> md = d.getMetadata();
        if (md != null && md.size() > 2 && md.containsKey(MD_TYPE)) return d;

        String key = extractRedisKey(d);
        if (key == null) return d;

        Object raw = null;
        try { raw = jedis.jsonGet(key); } catch (Exception e) { log.debug("jsonGet failed for key={}", key, e); }
        if (raw == null) return d;

        Map<String, Object> json = mapper.convertValue(raw, new TypeReference<Map<String, Object>>() {});
        String content = Objects.toString(json.getOrDefault("content", d.getText()), "");
        Map<String, Object> merged = new LinkedHashMap<>(json);
        merged.remove("content"); merged.remove("embedding");
        if (md != null) merged.putAll(md);
        // Сохраняем ключ Redis в метаданных вместо изменения id документа
        merged.putIfAbsent("redis_key", key);
        return new Document(content, merged);
    }

    private String extractRedisKey(Document d) {
        if (d.getId() != null) return d.getId();
        Map<String, Object> md = d.getMetadata();
        if (md == null) return null;
        Object k = md.getOrDefault("key", md.getOrDefault("id", md.get("redis_key")));
        return k != null ? String.valueOf(k) : null;
    }

    private Document resolveParent(String parentId) {
        if (parentId == null) return null;
        Document cached = parentById.get(parentId);
        if (cached != null) return cached;

        // Берём только родителей, а сравнение по node_id делаем в Java
        List<Document> parents = vectorStore.similaritySearch(
                SearchRequest.builder()
                        .query("parent")               // любой непустой запрос
                        .topK(256)                     // с запасом, чтобы покрыть всех родителей
                        .filterExpression("type == 'parent'")
                        .build()
        );

        for (Document d : parents) {
            Object nid = d.getMetadata().get(MD_NODE_ID);
            if (nid != null && parentId.equals(String.valueOf(nid))) {
                Document hydrated = hydrateFromRedis(d);
                parentById.put(parentId, hydrated);
                return hydrated;
            }
        }

        log.warn("Parent not found in vectorStore for id={}", parentId);
        return null;
    }

    private String expandQueryPRF(List<Document> docs, int m, int topTerms) {
        Map<String, Integer> df = new HashMap<>();
        Map<String, Integer> tf = new HashMap<>();
        int use = Math.min(m, docs.size());

        for (int i = 0; i < use; i++) {
            Document d = docs.get(i);
            Set<String> seen = new HashSet<>();
            List<String> toks = tokenize(String.valueOf(d.getMetadata().get(MD_TITLE)) + " " + d.getText());
            for (String t : toks) {
                tf.merge(t, 1, Integer::sum);
                if (seen.add(t)) df.merge(t, 1, Integer::sum);
            }
        }
        int N = Math.max(1, use);
        record Term(String t, double score) {}
        List<Term> list = tf.entrySet().stream()
                .map(e -> new Term(e.getKey(), e.getValue() * Math.log(1.0 + (double) N / Math.max(1, df.getOrDefault(e.getKey(), 1)))))
                .sorted(Comparator.comparingDouble((Term x) -> x.score).reversed())
                .limit(topTerms)
                .toList();

        String extra = list.stream().map(t -> t.t).collect(Collectors.joining(" "));
        log.debug("PRF terms: {}", extra);
        return extra;
    }

    private List<Document> rrfFuse(List<Document> a, List<Document> b, int k) {
        Map<String, Double> score = new HashMap<>();
        for (int i = 0; i < a.size(); i++) {
            Document d = a.get(i);
            String key = docKey(d);
            score.merge(key, 1.0 / (k + i + 1.0), Double::sum);
        }
        for (int i = 0; i < b.size(); i++) {
            Document d = b.get(i);
            String key = docKey(d);
            score.merge(key, 1.0 / (k + i + 1.0), Double::sum);
        }
        return Stream.concat(a.stream(), b.stream())
                .collect(Collectors.toMap(this::docKey, d -> d, (d1, d2) -> d1))
                .values().stream()
                .sorted((d1, d2) -> Double.compare(score.getOrDefault(docKey(d2), 0.0), score.getOrDefault(docKey(d1), 0.0)))
                .toList();
    }

    private String docKey(Document d) {
        if (d.getId() != null && !d.getId().isBlank()) return d.getId();
        Object nid = d.getMetadata() != null ? d.getMetadata().get(MD_NODE_ID) : null;
        return nid != null ? nid.toString() : Integer.toHexString(System.identityHashCode(d));
    }

    private static double duplicatePenalty(List<Document> kids) {
        Map<String, Long> byBase = kids.stream()
                .map(d -> baseNodeId((String) d.getMetadata().get(MD_NODE_ID)))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        long extra = byBase.values().stream().mapToLong(c -> Math.max(0, c - 1)).sum();
        return extra * 0.2;
    }

    private void debugTypeStats(List<Document> docs) {
        Map<String, Long> byType = docs.stream()
                .map(this::hydrateFromRedis)
                .collect(Collectors.groupingBy(d -> String.valueOf(d.getMetadata().get(MD_TYPE)), LinkedHashMap::new, Collectors.counting()));
        log.info("by type: {}", byType);
        if (!docs.isEmpty()) log.debug("sample meta: {}", docs.get(0).getMetadata());
    }

    // --- Токены / строки / числа ---

    private static final Set<String> STOP = Set.of(
            "и","в","во","на","о","об","от","до","за","над","под","по","из","у","к","с","для","это","как","что",
            "the","a","an","of","to","in","on","for","and","or","is","are","be","as","at","by","with","from"
    );

    private static List<String> tokenize(String s) {
        if (s == null) return List.of();
        return Arrays.stream(s.toLowerCase()
                        .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                        .trim().split("\\s+"))
                .filter(t -> t.length() >= 3 && !STOP.contains(t))
                .toList();
    }

    private static Set<String> tokens(String s) { return new LinkedHashSet<>(tokenize(s)); }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || (a.isEmpty() && b.isEmpty())) return 0.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        Set<String> larger = a.size() >= b.size() ? a : b;
        Set<String> smaller = a.size() < b.size() ? a : b;
        for (String s : smaller) if (larger.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    private static boolean isLeaf(Document d) {
        Object t = d.getMetadata().get(MD_TYPE);
        return t != null && "leaf".equalsIgnoreCase(String.valueOf(t).trim());
    }

    private static String safeText(String s) { return s == null ? "" : s; }

    private static String preview(String s, int n) {
        if (s == null) return "";
        s = s.strip();
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    private static String strOr(Map<String, Object> md, String... keys) {
        for (String k : keys) {
            Object v = md.get(k);
            if (v instanceof String s && !s.isBlank()) return s;
        }
        return null;
    }

    private static int intOr(Map<String, Object> md, int def, String... keys) {
        Integer v = intOrNull(md, keys);
        return v == null ? def : v;
    }

    private static Integer intOrNull(Map<String, Object> md, String... keys) {
        for (String k : keys) {
            Object v = md.get(k);
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s) { try { return Integer.parseInt(s.trim()); } catch (Exception ignore) {} }
        }
        return null;
    }

    private static double doubleOr(Object v, double def) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) try { return Double.parseDouble(s); } catch (Exception ignore) {}
        return def;
    }

    private static String baseNodeId(String nodeId) {
        if (nodeId == null) return null;
        int i = nodeId.indexOf('#');
        return i >= 0 ? nodeId.substring(0, i) : nodeId;
    }
}
