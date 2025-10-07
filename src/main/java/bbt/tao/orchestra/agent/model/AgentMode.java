package bbt.tao.orchestra.agent.model;

import java.util.Locale;

public enum AgentMode {
    STREAM,
    BLOCKING;

    public static AgentMode from(String value, AgentMode defaultMode) {
        if (value == null || value.isBlank()) {
            return defaultMode;
        }
        try {
            return AgentMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return defaultMode;
        }
    }

    public boolean isStreaming() {
        return this == STREAM;
    }
}
