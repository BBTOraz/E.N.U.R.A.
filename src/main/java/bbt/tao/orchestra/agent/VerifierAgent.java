package bbt.tao.orchestra.agent;

import bbt.tao.orchestra.agent.AgentChatClientRegistry;
import bbt.tao.orchestra.agent.VerificationResult;
import bbt.tao.orchestra.agent.model.AgentEvent;
import bbt.tao.orchestra.agent.model.AgentEventPublisher;
import bbt.tao.orchestra.agent.model.AgentRequestContext;
import bbt.tao.orchestra.agent.model.AgentRole;
import bbt.tao.orchestra.agent.model.AgentScratchpad;
import bbt.tao.orchestra.agent.model.AgentStage;
import bbt.tao.orchestra.agent.model.AgentVisibility;
import bbt.tao.orchestra.agent.model.VerifierOutcome;
import bbt.tao.orchestra.service.rag.PreloadingDocumentRetriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VerifierAgent {

    private static final Logger log = LoggerFactory.getLogger(VerifierAgent.class);
    private static final Pattern JSON_BLOCK = Pattern.compile("\\n?\\s*(\\{[\\n\\r\\s\\S]*})\\s*\\n?$");
    private static final int PROMPT_PREVIEW_LIMIT = 400;
    private static final int RESPONSE_PREVIEW_LIMIT = 400;

    private final AgentChatClientRegistry chatClientRegistry;
    private final ObjectMapper mapper = new ObjectMapper();

    public VerifierAgent(AgentChatClientRegistry chatClientRegistry) {
        this.chatClientRegistry = chatClientRegistry;
    }

    public Mono<VerifierOutcome> verify(AgentRequestContext context,
                                        AgentScratchpad scratchpad,
                                        String draftAnswer,
                                        AgentEventPublisher publisher) {
        return Mono.fromCallable(() -> doVerify(context, scratchpad, draftAnswer, publisher))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private VerifierOutcome doVerify(AgentRequestContext context,
                                     AgentScratchpad scratchpad,
                                     String draftAnswer,
                                     AgentEventPublisher publisher) throws Exception {
        publisher.publish(AgentEvent.of(AgentStage.VERIFICATION_STARTED, AgentVisibility.TRACE,
                "Verification", "Verifier is reviewing the draft"));

        publisher.publish(AgentEvent.of(AgentStage.VERIFICATION_PROGRESS, AgentVisibility.TRACE,
                "Verification", "Checking facts and relevance"));

        List<Document> documents = scratchpad.ragDocuments();
        String toolInfo = scratchpad.selectedTool()
                .map(tool -> tool.getToolDefinition().name())
                .orElse("not used");

        String prompt = buildPrompt(context, documents, toolInfo, draftAnswer);
        ChatClient chatClient = chatClientRegistry.getClient(context.verifierProvider(), AgentRole.VERIFIER);

        if (log.isDebugEnabled()) {
            log.debug("VerifierAgent: conversation={} verifierProvider={} docs={} promptPreview={}",
                    context.conversationId(),
                    context.verifierProvider().id(),
                    summarizeDocuments(documents),
                    truncate(prompt, PROMPT_PREVIEW_LIMIT));
        }

        String content = chatClient
                .prompt()
                .user(prompt)
                .advisors(spec -> spec
                        // НЕ используем ChatMemory - промежуточные диалоги агентов не сохраняем
                        // Сохранение истории происходит вручную в контроллере только для финального ответа
                        .param(PreloadingDocumentRetriever.CONTEXT_KEY, documents))
                .call()
                .content();

        if (log.isDebugEnabled()) {
            log.debug("VerifierAgent: conversation={} rawResponsePreview={}",
                    context.conversationId(),
                    truncate(content, RESPONSE_PREVIEW_LIMIT));
        }

        VerificationResult result;
        try {
            result = parseResult(content);
        } catch (Exception ex) {
            String preview = truncate(content == null ? "null" : content.replaceAll("\\s+", " "), 500);
            log.warn("VerifierAgent: parse failed, attempting to extract JSON. err={} preview={}", ex.getMessage(), preview);
            String extracted = tryExtractJson(content);
            result = parseResult(extracted);
        }

        String explanation = buildExplanation(result);
        publisher.publish(AgentEvent.of(AgentStage.VERIFICATION_FEEDBACK, AgentVisibility.TRACE,
                "Verification", explanation)
                .withData(Map.of(
                        "ok", result.isOk(),
                        "reasons", result.getReasons(),
                        "requiredChanges", result.getRequiredChanges() == null ? "" : result.getRequiredChanges()
                )));

        return new VerifierOutcome(result, explanation);
    }

    private String buildPrompt(AgentRequestContext context,
                               List<Document> documents,
                               String toolInfo,
                               String draft) {
        StringJoiner docs = new StringJoiner("\n");
        for (int i = 0; i < documents.size(); i++) {
            var doc = documents.get(i);
            String title = String.valueOf(doc.getMetadata().getOrDefault("title", "Document " + (i + 1)));
            docs.add((i + 1) + ". " + title);
        }

        return "User request:\n" + context.userMessage() + "\n\n" +
                "Draft answer:\n" + draft + "\n\n" +
                "Solver provider: " + context.solverProvider().id() + "\n" +
                "Verifier provider: " + context.verifierProvider().id() + "\n" +
                "Selected tool: " + toolInfo + "\n" +
                "RAG documents:\n" + (documents.isEmpty() ? "- none" : docs.toString()) + "\n\n" +
                "Return ONLY JSON matching {\"ok\": boolean, \"reasons\": string[], \"requiredChanges\": string}.";
    }

    private VerificationResult parseResult(String content) throws Exception {
        JsonNode node = mapper.readTree(content);
        VerificationResult res = mapper.treeToValue(node, VerificationResult.class);
        if (res.getReasons() == null) {
            res.setReasons(List.of());
        }
        if (!res.isOk() && (res.getRequiredChanges() == null || res.getRequiredChanges().isBlank())) {
            res.setRequiredChanges("Specify which facts or format must be corrected.");
        }
        return res;
    }

    private String buildExplanation(VerificationResult result) {
        if (result.isOk()) {
            return "Verification finished, no issues.";
        }
        if (result.getRequiredChanges() != null && !result.getRequiredChanges().isBlank()) {
            return "Needs fixes: " + result.getRequiredChanges();
        }
        if (result.getReasons() != null && !result.getReasons().isEmpty()) {
            return "Issues found: " + String.join("; ", result.getReasons());
        }
        return "Verification failed, please clarify issues.";
    }

    private String tryExtractJson(String s) {
        if (s == null) return "{}";
        Matcher m = JSON_BLOCK.matcher(s.trim());
        if (m.find()) {
            return m.group(1);
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return "{}";
    }

    private String truncate(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit) + "...";
    }

    private List<String> summarizeDocuments(List<Document> docs) {
        return docs.stream()
                .limit(5)
                .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("title", doc.getId())))
                .toList();
    }
}
