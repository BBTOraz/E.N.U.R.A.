package bbt.tao.orchestra.agent.model;

import java.util.Objects;

public record AgentRequestContext(
        String conversationId,
        String userMessage,
        AgentProvider solverProvider,
        AgentProvider verifierProvider,
        AgentMode mode,
        AgentVisibility visibility,
        boolean providerDefault,
        boolean thinkingEnabled
) {
    public AgentRequestContext {
        conversationId = Objects.requireNonNull(conversationId, "conversationId");
        userMessage = Objects.requireNonNull(userMessage, "userMessage");
        solverProvider = Objects.requireNonNull(solverProvider, "solverProvider");
        verifierProvider = Objects.requireNonNull(verifierProvider, "verifierProvider");
        mode = Objects.requireNonNull(mode, "mode");
        visibility = Objects.requireNonNull(visibility, "visibility");
    }

    public String solverConversationId() {
        return conversationId + "::solver";
    }

    public String verifierConversationId() {
        return conversationId + "::verifier";
    }
}
