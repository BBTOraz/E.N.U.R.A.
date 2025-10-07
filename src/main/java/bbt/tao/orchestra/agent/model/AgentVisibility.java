package bbt.tao.orchestra.agent.model;

import java.util.Locale;

public enum AgentVisibility {
    TRACE,
    HINT;

    public static AgentVisibility from(String value, AgentVisibility defaultVisibility) {
        if (value == null || value.isBlank()) {
            return defaultVisibility;
        }
        try {
            return AgentVisibility.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return defaultVisibility;
        }
    }
}
