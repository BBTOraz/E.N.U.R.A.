package bbt.tao.orchestra.agent;

import bbt.tao.orchestra.agent.model.AgentEvent;
import bbt.tao.orchestra.agent.model.AgentEventPublisher;
import bbt.tao.orchestra.agent.model.AgentRequestContext;
import bbt.tao.orchestra.agent.model.AgentScratchpad;
import bbt.tao.orchestra.agent.model.AgentStage;
import bbt.tao.orchestra.agent.model.AgentVisibility;
import bbt.tao.orchestra.agent.model.SolverResult;
import bbt.tao.orchestra.agent.model.VerifierOutcome;
import bbt.tao.orchestra.agent.store.AgentScratchpadStore;
import bbt.tao.orchestra.agent.VerificationResult;
import bbt.tao.orchestra.service.rag.PreloadingDocumentRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int MAX_ITERS = 3;

    private final SolverAgent solver;
    private final VerifierAgent verifier;
    private final DocumentRetriever documentRetriever;
    private final AgentScratchpadStore scratchpadStore;

    public AgentOrchestrator(SolverAgent solver,
                             VerifierAgent verifier,
                             DocumentRetriever documentRetriever,
                             AgentScratchpadStore scratchpadStore) {
        this.solver = solver;
        this.verifier = verifier;
        this.documentRetriever = documentRetriever;
        this.scratchpadStore = scratchpadStore;
    }

    public Flux<AgentEvent> run(AgentRequestContext context) {
        return Flux.create(sink -> {
            AgentScratchpad scratchpad = new AgentScratchpad(context, scratchpadStore);
            AtomicBoolean cancelled = new AtomicBoolean(false);

            AgentEventPublisher publisher = event -> {
                if (!cancelled.get()) {
                    sink.next(event);
                }
            };

            publisher.publish(AgentEvent.of(AgentStage.SOLVER_STARTED, AgentVisibility.TRACE,
                    "Solver", "Iteration 1 started"));

            iterate(context, scratchpad, publisher, 1, context.userMessage())
                    .subscribe(answer -> {
                        if (cancelled.get()) {
                            return;
                        }
                        publisher.publish(AgentEvent.of(AgentStage.FINAL_ANSWER, AgentVisibility.HINT,
                                "Answer", "Result delivered")
                                .withData(buildFinalData(context, scratchpad, answer)));
                        scratchpad.clear();
                        sink.complete();
                    }, error -> {
                        log.error("AgentOrchestrator: error during execution", error);
                        if (!cancelled.get()) {
                            publisher.publish(AgentEvent.of(AgentStage.ERROR, AgentVisibility.HINT,
                                    "Error", error.getMessage() == null ? "Unknown error" : error.getMessage()));
                            sink.error(error);
                        }
                    });

            sink.onCancel(() -> {
                cancelled.set(true);
                scratchpad.clear();
            });
        });
    }

    private Mono<String> iterate(AgentRequestContext context,
                                 AgentScratchpad scratchpad,
                                 AgentEventPublisher publisher,
                                 int iteration,
                                 String currentMessage) {
        if (iteration > MAX_ITERS) {
            log.info("AgentOrchestrator: reached max iterations={}, returning last draft", MAX_ITERS);
            return Mono.justOrEmpty(scratchpad.draft().orElse(""));
        }

        if (iteration > 1) {
            publisher.publish(AgentEvent.of(AgentStage.SOLVER_STARTED, AgentVisibility.TRACE,
                    "Solver", "Iteration " + iteration + " started"));
        }

        return retrieveDocuments(currentMessage)
                .doOnNext(scratchpad::setRagDocuments)
                .doOnNext(docs -> publisher.publish(buildRagEvent(docs)))
                .flatMap(docs -> solver.solve(context, scratchpad, docs, publisher)
                        .flatMap(result -> verifier.verify(context, scratchpad, result.draft(), publisher)
                                .flatMap(outcome -> handleVerifierOutcome(context, scratchpad, publisher,
                                        iteration, currentMessage, result, outcome))
                        ));
    }

    private Mono<List<Document>> retrieveDocuments(String message) {
        return Mono.fromCallable(() -> documentRetriever.retrieve(new Query(message)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> handleVerifierOutcome(AgentRequestContext context,
                                               AgentScratchpad scratchpad,
                                               AgentEventPublisher publisher,
                                               int iteration,
                                               String originalMessage,
                                               SolverResult solverResult,
                                               VerifierOutcome outcome) {
        if (outcome.result().isOk()) {
            log.info("AgentOrchestrator: verification succeeded on iteration {}", iteration);
            return Mono.justOrEmpty(solverResult.draft());
        }
        log.info("AgentOrchestrator: verification failed on iteration {}, preparing next iteration", iteration);
        String nextMessage = buildNextIterationPrompt(originalMessage, outcome.result());
        return iterate(context, scratchpad, publisher, iteration + 1, nextMessage);
    }

    private AgentEvent buildRagEvent(List<Document> docs) {
        Map<String, Object> data = new HashMap<>();
        data.put("count", docs.size());
        if (!CollectionUtils.isEmpty(docs)) {
            data.put("titles", docs.stream()
                    .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("title", doc.getId())))
                    .toList());
        }
        String message = docs.isEmpty() ? "No RAG documents found" : "Prepared " + docs.size() + " document(s)";
        return AgentEvent.of(AgentStage.RAG_CONTEXT, AgentVisibility.TRACE, "RAG", message)
                .withData(data);
    }

    private Map<String, Object> buildFinalData(AgentRequestContext context,
                                               AgentScratchpad scratchpad,
                                               String answer) {
        Map<String, Object> data = new HashMap<>();
        data.put("solverProvider", context.solverProvider().id());
        data.put("verifierProvider", context.verifierProvider().id());
        data.put("mode", context.mode().name().toLowerCase(Locale.ROOT));
        data.put("answer", answer);
        data.put("documents", scratchpad.ragDocuments().stream()
                .map(doc -> String.valueOf(doc.getMetadata().getOrDefault("title", doc.getId())))
                .toList());
        data.put("tool", scratchpad.selectedTool().map(tool -> tool.getToolDefinition().name()).orElse("none"));
        return data;
    }

    private String buildNextIterationPrompt(String originalMessage, VerificationResult verificationResult) {
        StringBuilder builder = new StringBuilder(originalMessage)
                .append("\n\nPlease fix the answer according to verifier feedback:\n");
        if (verificationResult.getRequiredChanges() != null && !verificationResult.getRequiredChanges().isBlank()) {
            builder.append(verificationResult.getRequiredChanges());
        } else if (verificationResult.getReasons() != null && !verificationResult.getReasons().isEmpty()) {
            builder.append(String.join("; ", verificationResult.getReasons()));
        } else {
            builder.append("Review facts and format, using the provided RAG documents.");
        }
        return builder.toString();
    }
}
