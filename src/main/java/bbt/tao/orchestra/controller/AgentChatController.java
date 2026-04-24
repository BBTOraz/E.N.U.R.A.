package bbt.tao.orchestra.controller;

import bbt.tao.orchestra.agent.AgentOrchestrator;
import bbt.tao.orchestra.agent.model.AgentEvent;
import bbt.tao.orchestra.agent.model.AgentMode;
import bbt.tao.orchestra.agent.model.AgentProvider;
import bbt.tao.orchestra.agent.model.AgentRequestContext;
import bbt.tao.orchestra.agent.model.AgentStage;
import bbt.tao.orchestra.agent.model.AgentVisibility;
import bbt.tao.orchestra.conf.AgentProperties;
import bbt.tao.orchestra.dto.api.OrchestraResponse;
import bbt.tao.orchestra.service.ConversationService;
import bbt.tao.orchestra.service.format.Summarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private final AgentOrchestrator orchestrator;
    private final Summarizer summarizer;
    private final AgentProperties properties;
    private final ConversationService conversationService;
    
    private final ConcurrentHashMap<String, AtomicBoolean> activeConversations = new ConcurrentHashMap<>();

    public AgentChatController(AgentOrchestrator orchestrator,
                               Summarizer summarizer,
                               AgentProperties properties,
                               ConversationService conversationService) {
        this.orchestrator = orchestrator;
        this.summarizer = summarizer;
        this.properties = properties;
        this.conversationService = conversationService;
    }

    @PostMapping(value = "/{conversationId}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgentEvent>> chat(@PathVariable String conversationId,
                                                  @RequestBody Map<String, Object> body,
                                                  @RequestParam(value = "provider", required = false) String providerQuery,
                                                  @RequestParam(value = "solverProvider", required = false) String solverProviderQuery,
                                                  @RequestParam(value = "verifierProvider", required = false) String verifierProviderQuery,
                                                  @RequestParam(value = "mode", required = false) String modeQuery,
                                                  @RequestParam(value = "visibility", required = false) String visibilityQuery,
                                                  @RequestParam(value = "thinking", required = false) String thinkingQuery) {
        String message = asString(body.get("message"));
        if (message == null || message.isBlank()) {
            return Flux.just(errorEvent("Field 'message' is required"));
        }

        AtomicBoolean lock = activeConversations.computeIfAbsent(conversationId, k -> new AtomicBoolean(false));
        if (!lock.compareAndSet(false, true)) {
            log.warn("[{}] Request rejected: conversation is busy", conversationId);
            return Flux.just(ServerSentEvent.<AgentEvent>builder(
                    AgentEvent.of(AgentStage.ERROR, AgentVisibility.HINT, "Busy", "Conversation is busy, please wait"))
                    .event("busy")
                    .build());
        }

        String providerInput = coalesce(asString(body.get("provider")), providerQuery);
        String solverInput = coalesce(asString(body.get("solverProvider")), solverProviderQuery);
        String verifierInput = coalesce(asString(body.get("verifierProvider")), verifierProviderQuery);
        String modeInput = coalesce(asString(body.get("mode")), modeQuery);
        String visibilityInput = coalesce(asString(body.get("visibility")), visibilityQuery);
        String thinkingInput = coalesce(asString(body.get("thinking")), thinkingQuery);

        var providerOpt = AgentProvider.from(providerInput);
        var solverOpt = AgentProvider.from(solverInput);
        var verifierOpt = AgentProvider.from(verifierInput);

        if ((providerInput != null && providerOpt.isEmpty())
                || (solverInput != null && solverOpt.isEmpty())
                || (verifierInput != null && verifierOpt.isEmpty())) {
            lock.set(false);
            return Flux.just(errorEvent("Unknown provider value"));
        }

        AgentProvider defaultProvider = AgentProvider.from(properties.getDefaultProvider()).orElse(AgentProvider.GROQ);
        AgentProvider solverProvider = solverOpt.or(() -> providerOpt).orElse(defaultProvider);
        AgentProvider verifierProvider = verifierOpt.or(() -> providerOpt).orElse(defaultProvider);
        boolean providerDefault = providerOpt.isEmpty() && solverOpt.isEmpty() && verifierOpt.isEmpty();

        AgentMode mode = AgentMode.from(modeInput, AgentMode.STREAM);
        AgentVisibility visibility = AgentVisibility.from(visibilityInput, AgentVisibility.TRACE);
        boolean thinkingEnabled = parseBoolean(thinkingInput);

        AgentRequestContext context = new AgentRequestContext(
                conversationId,
                message,
                solverProvider,
                verifierProvider,
                mode,
                visibility,
                providerDefault,
                thinkingEnabled
        );

        log.info("[{}] AgentChatController START: solver={}, verifier={}, mode={}, visibility={}, thinking={}, message='{}'",
                conversationId, context.solverProvider(), context.verifierProvider(), mode, visibility, thinkingEnabled, message);

        final String[] finalAnswer = new String[1];
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final String originalConversationId = conversationId;
        final String originalUserMessage = message;
        final long startTime = System.currentTimeMillis();

        Flux<AgentEvent> eventFlux = orchestrator.run(context)
                .doOnSubscribe(s -> log.info("[{}] Stream SUBSCRIBED", originalConversationId))
                .doOnNext(event -> {
                    log.debug("[{}] Event emitted: stage={}, visibility={}",
                            originalConversationId, event.stage(), event.visibility());
                    
                    if (event.stage() == AgentStage.FINAL_ANSWER) {
                        Object answerObj = event.data().get("answer");
                        if (answerObj != null) {
                            finalAnswer[0] = String.valueOf(answerObj);
                            log.info("[{}] FINAL_ANSWER received: length={}",
                                    originalConversationId, finalAnswer[0].length());
                        } else {
                            log.warn("[{}] FINAL_ANSWER event has no 'answer' in data", originalConversationId);
                        }
                    }
                })
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("[{}] Stream COMPLETED successfully in {}ms", originalConversationId, duration);
                    
                    if (finalAnswer[0] != null && !finalAnswer[0].isBlank()) {
                        log.info("[{}] Saving conversation pair: userMessageLength={}, assistantMessageLength={}",
                                originalConversationId, originalUserMessage.length(), finalAnswer[0].length());
                        
                        conversationService.saveConversationPair(originalConversationId, originalUserMessage, finalAnswer[0])
                                .doOnSuccess(v -> log.info("[{}] Conversation pair saved successfully", originalConversationId))
                                .subscribe(
                                        null,
                                        ex -> log.error("[{}] Failed to save conversation pair: {}", 
                                                originalConversationId, ex.getMessage(), ex)
                                );
                    } else {
                        log.warn("[{}] Skipping save: no final answer received", originalConversationId);
                    }
                })
                .doOnError(error -> {
                    long duration = System.currentTimeMillis() - startTime;
                    log.error("[{}] Stream ERROR after {}ms: {}",
                            originalConversationId, duration, error.getMessage(), error);
                })
                .doOnCancel(() -> {
                    cancelled.set(true);
                    long duration = System.currentTimeMillis() - startTime;
                    log.warn("[{}] Stream CANCELLED by client after {}ms. Not saving partial response.",
                            originalConversationId, duration);
                })
                .doFinally(signalType -> {
                    lock.set(false);
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("[{}] Stream FINALIZED: signal={}, duration={}ms, cancelled={}",
                            originalConversationId, signalType, duration, cancelled.get());
                });

        return eventFlux
                .map(event -> ServerSentEvent.<AgentEvent>builder(event)
                        .event(event.stage().name().toLowerCase(Locale.ROOT))
                        .build())
                .onErrorResume(ex -> Flux.just(errorEvent(ex.getMessage() == null ? "Agent processing error" : ex.getMessage())));
    }

    @PostMapping(value = "/envelope/{conversationId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<OrchestraResponse> chatEnvelope(@PathVariable String conversationId,
                                                @RequestBody Map<String, Object> body,
                                                @RequestParam(value = "provider", required = false) String providerQuery,
                                                @RequestParam(value = "solverProvider", required = false) String solverProviderQuery,
                                                @RequestParam(value = "verifierProvider", required = false) String verifierProviderQuery,
                                                @RequestParam(value = "mode", required = false) String modeQuery,
                                                @RequestParam(value = "visibility", required = false) String visibilityQuery,
                                                @RequestParam(value = "thinking", required = false) String thinkingQuery) {
        String message = asString(body.get("message"));
        if (message == null || message.isBlank()) {
            return Mono.just(new OrchestraResponse(null, null));
        }

        String providerInput = coalesce(asString(body.get("provider")), providerQuery);
        String solverInput = coalesce(asString(body.get("solverProvider")), solverProviderQuery);
        String verifierInput = coalesce(asString(body.get("verifierProvider")), verifierProviderQuery);
        String modeInput = coalesce(asString(body.get("mode")), modeQuery);
        String visibilityInput = coalesce(asString(body.get("visibility")), visibilityQuery);
        String thinkingInput = coalesce(asString(body.get("thinking")), thinkingQuery);

        var providerOpt = AgentProvider.from(providerInput);
        var solverOpt = AgentProvider.from(solverInput);
        var verifierOpt = AgentProvider.from(verifierInput);

        if ((providerInput != null && providerOpt.isEmpty())
                || (solverInput != null && solverOpt.isEmpty())
                || (verifierInput != null && verifierOpt.isEmpty())) {
            return Mono.error(new IllegalArgumentException("Unknown provider value"));
        }

        AgentProvider defaultProvider = AgentProvider.from(properties.getDefaultProvider()).orElse(AgentProvider.GROQ);
        AgentProvider solverProvider = solverOpt.or(() -> providerOpt).orElse(defaultProvider);
        AgentProvider verifierProvider = verifierOpt.or(() -> providerOpt).orElse(defaultProvider);
        AgentMode mode = AgentMode.from(modeInput, AgentMode.BLOCKING);
        AgentVisibility visibility = AgentVisibility.from(visibilityInput, AgentVisibility.HINT);
        boolean thinkingEnabled = parseBoolean(thinkingInput);

        AgentRequestContext context = new AgentRequestContext(
                conversationId,
                message,
                solverProvider,
                verifierProvider,
                mode,
                visibility,
                providerOpt.isEmpty() && solverOpt.isEmpty() && verifierOpt.isEmpty(),
                thinkingEnabled
        );

        Mono<OrchestraResponse> responseMono = orchestrator.run(context)
                .filter(event -> event.stage() == AgentStage.FINAL_ANSWER)
                .next()
                .flatMap(event -> {
                    Map<String, Object> data = event.data();
                    String answer = String.valueOf(data.getOrDefault("answer", ""));
                    String summary = String.valueOf(data.getOrDefault("summary", ""));
                    
                    // Сохраняем пару сообщений в транзакции
                    return conversationService.saveConversationPair(conversationId, message, answer)
                            .then(Mono.fromCallable(() -> {
                                String finalSummary = org.springframework.util.StringUtils.hasText(summary) 
                                        ? summary 
                                        : summarizer.summarize(answer);
                                return new OrchestraResponse(answer, finalSummary);
                            }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                            .onErrorResume(ex -> {
                                log.error("Failed to save conversation pair for {}: {}", 
                                        conversationId, ex.getMessage());
                                String finalSummary = org.springframework.util.StringUtils.hasText(summary)
                                        ? summary 
                                        : summarizer.summarize(answer);
                                return Mono.just(new OrchestraResponse(answer, finalSummary));
                            });
                })
                .switchIfEmpty(Mono.just(new OrchestraResponse(null, null)))
                .onErrorReturn(new OrchestraResponse(null, null));

        return responseMono;
    }

    private ServerSentEvent<AgentEvent> errorEvent(String message) {
        AgentEvent event = AgentEvent.of(AgentStage.ERROR, AgentVisibility.HINT, "Error", message);
        return ServerSentEvent.<AgentEvent>builder(event)
                .event(AgentStage.ERROR.name().toLowerCase(Locale.ROOT))
                .build();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String coalesce(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private boolean parseBoolean(String value) {
        if (value == null) {
            return false;
        }
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("1");
    }
}
