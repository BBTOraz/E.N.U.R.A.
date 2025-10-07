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
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/agent/chat")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private final AgentOrchestrator orchestrator;
    private final Summarizer summarizer;
    private final AgentProperties properties;
    private final ConversationService conversationService;

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

        log.info("AgentChatController: convId={}, solver={}, verifier={}, mode={}, visibility={}, thinking={}, message='{}'",
                conversationId, context.solverProvider(), context.verifierProvider(), mode, visibility, thinkingEnabled, message);

        Mono<Void> userPersist = conversationService.saveMessage(conversationId, message, "USER")
                .doOnError(ex -> log.warn("Failed to persist user message for conversation {}", conversationId, ex))
                .onErrorResume(ex -> Mono.empty())
                .then();

        Flux<AgentEvent> eventFlux = orchestrator.run(context)
                .concatMap(event -> {
                    if (event.stage() == AgentStage.FINAL_ANSWER) {
                        String answer = String.valueOf(event.data().get("answer"));
                        return conversationService.saveMessage(conversationId, answer, "ASSISTANT")
                                .doOnError(ex -> log.warn("Failed to persist assistant answer for conversation {}", conversationId, ex))
                                .onErrorResume(ex -> Mono.empty())
                                .thenReturn(event);
                    }
                    return Mono.just(event);
                });

        return userPersist.thenMany(eventFlux)
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

        Mono<Void> userPersist = conversationService.saveMessage(conversationId, message, "USER")
                .doOnError(ex -> log.warn("Failed to persist user message for conversation {}", conversationId, ex))
                .onErrorResume(ex -> Mono.empty())
                .then();

        Mono<OrchestraResponse> responseMono = orchestrator.run(context)
                .filter(event -> event.stage() == AgentStage.FINAL_ANSWER)
                .next()
                .flatMap(event -> {
                    String answer = String.valueOf(event.data().get("answer"));
                    Mono<Void> assistantPersist = conversationService.saveMessage(conversationId, answer, "ASSISTANT")
                            .doOnError(ex -> log.warn("Failed to persist assistant answer for conversation {}", conversationId, ex))
                            .onErrorResume(ex -> Mono.empty())
                            .then();
                    return assistantPersist.then(Mono.fromCallable(() -> {
                        String summary = summarizer.summarize(answer);
                        return new OrchestraResponse(answer, summary);
                    }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()));
                })
                .switchIfEmpty(Mono.just(new OrchestraResponse(null, null)))
                .onErrorReturn(new OrchestraResponse(null, null));

        return userPersist.then(responseMono);
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
