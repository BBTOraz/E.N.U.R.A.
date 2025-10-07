package bbt.tao.orchestra.agent.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public record AgentEvent(
        AgentStage stage,
        AgentVisibility visibility,
        String title,
        String message,
        Map<String, Object> data
) {
    public AgentEvent {
        stage = Objects.requireNonNull(stage, "stage");
        visibility = Objects.requireNonNullElse(visibility, AgentVisibility.TRACE);
        title = title == null ? "" : title;
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Collections.unmodifiableMap(data);
    }

    public static AgentEvent of(AgentStage stage, AgentVisibility visibility, String title, String message) {
        return new AgentEvent(stage, visibility, title, message, Map.of());
    }

    public AgentEvent withData(Map<String, Object> extra) {
        return new AgentEvent(stage, visibility, title, message, extra);
    }
}
