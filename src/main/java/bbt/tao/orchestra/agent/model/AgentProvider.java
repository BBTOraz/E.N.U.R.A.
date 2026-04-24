package bbt.tao.orchestra.agent.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum AgentProvider {
    GROQ("groq"),
    OLLAMA("ollama");

    private final String id;

    AgentProvider(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Optional<AgentProvider> from(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(provider -> provider.id.equals(normalized))
                .findFirst();
    }
}
