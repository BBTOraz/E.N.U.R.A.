package bbt.tao.orchestra.agent;

import bbt.tao.orchestra.agent.model.AgentAnswer;
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
import bbt.tao.orchestra.service.format.AgentAnswerFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
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
    private final AgentAnswerFormatter answerFormatter;

    public AgentOrchestrator(SolverAgent solver,
                             VerifierAgent verifier,
                             DocumentRetriever documentRetriever,
                             AgentScratchpadStore scratchpadStore,
                             AgentAnswerFormatter answerFormatter) {
        this.solver = solver;
        this.verifier = verifier;
        this.documentRetriever = documentRetriever;
        this.scratchpadStore = scratchpadStore;
        this.answerFormatter = answerFormatter;
    }
    

    public Flux<AgentEvent> run(AgentRequestContext context) {
        log.info("Запуск AgentOrchestrator.run() для [{}]", context.conversationId());

        return Flux.<AgentEvent>create(sink -> {
            AgentScratchpad scratchpad = new AgentScratchpad(context, scratchpadStore);
            scratchpad.initializeBaseQuery(context.userMessage());
            AtomicBoolean cancelled = new AtomicBoolean(false);

            AgentEventPublisher publisher = event -> {
                if (!cancelled.get()) {
                    log.debug("Публикация события [{}]: {}", context.conversationId(), event.stage());
                    sink.next(event);
                } else {
                    log.debug("Пропуск события [{}] (отменено): {}", context.conversationId(), event.stage());
                }
            };

            log.info("Начало итерации 1 для [{}]", context.conversationId());
            publisher.publish(AgentEvent.of(AgentStage.SOLVER_STARTED, AgentVisibility.TRACE,
                    "Solver", "Итерация 1 начата"));

            iterate(context, scratchpad, publisher, 1, context.userMessage())
                    .subscribe(answer -> {
                        if (cancelled.get()) {
                            log.warn("Итерация завершена, но поток был отменён [{}]", context.conversationId());
                            return;
                        }
                        log.info("Готов финальный ответ, публикация события FINAL_ANSWER [{}]", context.conversationId());
                        publisher.publish(AgentEvent.of(AgentStage.FINAL_ANSWER, AgentVisibility.HINT,
                                "Answer", "Результат доставлен")
                                .withData(buildFinalData(context, scratchpad, answer)));
                        scratchpad.clear();
                        log.info("Завершение sink (вызов sink.complete()) [{}]", context.conversationId());
                        sink.complete();
                    }, error -> {
                        log.error("Ошибка AgentOrchestrator во время выполнения [{}]: {}",
                                context.conversationId(), error.getMessage(), error);
                        if (!cancelled.get()) {
                            publisher.publish(AgentEvent.of(AgentStage.ERROR, AgentVisibility.HINT,
                                    "Error", error.getMessage() == null ? "Неизвестная ошибка" : error.getMessage()));
                            sink.error(error);
                        }
                    });

            sink.onCancel(() -> {
                log.warn("Поток AgentOrchestrator ОТМЕНЁН [{}]", context.conversationId());
                cancelled.set(true);
                scratchpad.clear();
            });
        }, FluxSink.OverflowStrategy.BUFFER)
        .doOnComplete(() -> log.info("AgentOrchestrator Flux завершён [{}]", context.conversationId()))
        .doOnError(e -> log.error("Ошибка в AgentOrchestrator Flux [{}]: {}", context.conversationId(), e.getMessage()))
        .doOnCancel(() -> log.warn("AgentOrchestrator Flux ОТМЕНЁН [{}]", context.conversationId()))
        .doFinally(signal -> log.info("AgentOrchestrator Flux финализирован [{}]: signal={}", context.conversationId(), signal));
    }

    private Mono<AgentAnswer> iterate(AgentRequestContext context,
                                      AgentScratchpad scratchpad,
                                      AgentEventPublisher publisher,
                                      int iteration,
                                      String currentMessage) {
        if (iteration > MAX_ITERS) {
            log.info("AgentOrchestrator: достигнуто максимальное число итераций = {}, форматирование последнего черновика", MAX_ITERS);
            String draft = scratchpad.draft().orElse("");
            return formatFinalAnswer(context, draft);
        }

        if (iteration > 1) {
            publisher.publish(AgentEvent.of(AgentStage.SOLVER_STARTED, AgentVisibility.TRACE,
                    "Solver", "Итерация " + iteration + " начата"));
        }

        return retrieveDocuments(context, scratchpad, iteration)
                .doOnNext(scratchpad::setRagDocuments)
                .doOnNext(docs -> publisher.publish(buildRagEvent(docs)))
                .flatMap(docs -> solver.solve(context, scratchpad, docs, publisher)
                        .flatMap(result -> verifier.verify(context, scratchpad, result.draft(), publisher)
                                .flatMap(outcome -> handleVerifierOutcome(context, scratchpad, publisher,
                                        iteration, currentMessage, result, outcome))
                        ));
    }

    private Mono<List<Document>> retrieveDocuments(AgentRequestContext context,
                                                   AgentScratchpad scratchpad,
                                                   int iteration) {
        List<Document> cached = scratchpad.ragDocuments();
        if (!cached.isEmpty()) {
            log.debug("AgentOrchestrator: используются кэшированные RAG-документы для conversation={} итерация={} ({} документ(ов))",
                    context.conversationId(), iteration, cached.size());
            return Mono.just(cached);
        }
        String query = scratchpad.baseQuery().orElse(context.userMessage());
        log.debug("AgentOrchestrator: получение RAG-документов для conversation={} итерация={} queryPreview='{}'",
                context.conversationId(),
                iteration,
                preview(query, 160));
        long start = System.nanoTime();
        return Mono.fromCallable(() -> documentRetriever.retrieve(new Query(query)))
                .doOnNext(docs -> log.info("AgentOrchestrator: RAG-поиск завершён за {} мс для conversation={} итерация={} ({} документ(ов))",
                        (System.nanoTime() - start) / 1_000_000,
                        context.conversationId(),
                        iteration,
                        docs.size()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<AgentAnswer> handleVerifierOutcome(AgentRequestContext context,
                                                    AgentScratchpad scratchpad,
                                                    AgentEventPublisher publisher,
                                                    int iteration,
                                                    String originalMessage,
                                                    SolverResult solverResult,
                                                    VerifierOutcome outcome) {
        if (outcome.result().isOk()) {
            log.info("Проверка успешна на итерации {}, форматирование финального ответа [{}]",
                    iteration, context.conversationId());
            return formatFinalAnswer(context, solverResult.draft());
        }
        log.info("Проверка не пройдена на итерации {}, подготовка следующей итерации [{}]",
                iteration, context.conversationId());
        String nextMessage = buildNextIterationPrompt(originalMessage, outcome.result());
        return iterate(context, scratchpad, publisher, iteration + 1, nextMessage);
    }

    private Mono<AgentAnswer> formatFinalAnswer(AgentRequestContext context, String draft) {
        log.info("Форматирование финального ответа [{}], длина черновика={}", context.conversationId(), draft.length());
        return Mono.fromCallable(() -> answerFormatter.format(context.userMessage(), draft))
                .doOnSuccess(answer -> log.info("Финальный ответ сформирован [{}]: fullAnswer='{}...', summary='{}...'",
                        context.conversationId(),
                        answer.fullAnswer().substring(0, Math.min(100, answer.fullAnswer().length())),
                        answer.summary().substring(0, Math.min(50, answer.summary().length()))))
                .doOnError(e -> log.error("Ошибка при форматировании финального ответа [{}]: {}",
                        context.conversationId(), e.getMessage(), e))
                .subscribeOn(Schedulers.boundedElastic());
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
                                               AgentAnswer answer) {
        Map<String, Object> data = new HashMap<>();
        data.put("solverProvider", context.solverProvider().id());
        data.put("verifierProvider", context.verifierProvider().id());
        data.put("mode", context.mode().name().toLowerCase(Locale.ROOT));
        data.put("answer", answer.fullAnswer());
        data.put("summary", answer.summary());
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

    private String preview(String value, int limit) {
        if (value == null) {
            return "";
        }
        String trimmed = value.strip();
        if (trimmed.length() <= limit) {
            return trimmed;
        }
        return trimmed.substring(0, limit) + "…";
    }
}
