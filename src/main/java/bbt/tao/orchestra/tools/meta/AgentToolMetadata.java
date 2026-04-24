package bbt.tao.orchestra.tools.meta;

import java.util.List;

public record AgentToolMetadata(
        String beanName,
        String methodName,
        String toolName,
        String description,
        List<String> examples
) {
}
