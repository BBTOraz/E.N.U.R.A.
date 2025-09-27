package bbt.tao.orchestra.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);
    private static final int MAX_ITERS = 3;

    private final SolverAgent solver;
    private final VerifierAgent verifier;

    public AgentOrchestrator(SolverAgent solver, VerifierAgent verifier) {
        this.solver = solver;
        this.verifier = verifier;
    }

    public Mono<String> run(String conversationId, String userMessage) {
        final String solverConv = conversationId + "::solver";
        final String verifierConv = conversationId + "::verifier";

        return Mono.defer(() -> iterate(1, null, userMessage, solverConv, verifierConv));
    }

    private Mono<String> iterate(int iter,
                                 String lastDraft,
                                 String currentMessage,
                                 String solverConv,
                                 String verifierConv) {
        if (iter > MAX_ITERS) {
            log.info("AgentOrchestrator: reached max iterations={}, returning last draft", MAX_ITERS);
            return Mono.just(lastDraft == null ? "" : lastDraft);
        }

        log.info("AgentOrchestrator: iteration {} started", iter);
        return solver.solve(solverConv, currentMessage)
                .flatMap(draft -> verifier.verify(verifierConv, currentMessage, draft)
                        .flatMap(v -> {
                            if (v.isOk()) {
                                log.info("AgentOrchestrator: verification OK on iter {}", iter);
                                return Mono.just(draft);
                            }
                            String nextMsg = currentMessage + "\n\nПожалуйста, исправь ответ согласно замечаниям проверяющего:" +
                                    "\n" + (v.getRequiredChanges() == null ? String.join("; ", v.getReasons()) : v.getRequiredChanges());
                            log.info("AgentOrchestrator: verification NOT OK, will iterate. reasons={}", v.getReasons());
                            return iterate(iter + 1, draft, nextMsg, solverConv, verifierConv);
                        })
                );
    }
}

