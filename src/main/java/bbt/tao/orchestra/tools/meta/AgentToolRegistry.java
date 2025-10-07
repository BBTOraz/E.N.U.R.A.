package bbt.tao.orchestra.tools.meta;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class AgentToolRegistry {

    private final List<Object> toolBeans;
    private final Map<String, AgentToolMetadata> metadataByToolName;

    public AgentToolRegistry(List<Object> toolBeans, Map<String, AgentToolMetadata> metadataByToolName) {
        this.toolBeans = List.copyOf(toolBeans);
        this.metadataByToolName = Collections.unmodifiableMap(metadataByToolName);
    }

    public List<Object> toolBeans() {
        return toolBeans;
    }

    public AgentToolMetadata metadata(String toolName) {
        return metadataByToolName.get(toolName);
    }
}
