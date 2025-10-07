package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.tools.meta.AgentToolMeta;
import bbt.tao.orchestra.tools.meta.AgentToolMetadata;
import bbt.tao.orchestra.tools.meta.AgentToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Configuration
public class ToolCallBackProviderConfig {

    private static final String NO_TOOL_BEANS_MESSAGE = "Не найдено ни одного бина с аннотациями @Tool и @AgentToolMeta";

    @Bean
    public AgentToolRegistry agentToolRegistry(ApplicationContext applicationContext) {
        List<Object> toolBeans = new ArrayList<>();
        Map<String, AgentToolMetadata> metadata = new HashMap<>();

        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = applicationContext.getBean(beanName);
            } catch (Exception ex) {
                log.debug("Skipping bean '{}' during tool registration: {}", beanName, ex.getMessage());
                continue;
            }

            Class<?> targetClass = AopUtils.getTargetClass(bean);
            if (targetClass == null) {
                continue;
            }

            boolean beanRegistered = false;
            for (Method method : targetClass.getMethods()) {
                Tool tool = method.getAnnotation(Tool.class);
                AgentToolMeta meta = method.getAnnotation(AgentToolMeta.class);

                if (tool == null && meta != null) {
                    log.warn("Метод {}.{} помечен @AgentToolMeta без @Tool и будет проигнорирован", targetClass.getSimpleName(), method.getName());
                    continue;
                }

                if (tool == null) {
                    continue;
                }

                if (meta == null) {
                    log.warn("Метод {}.{} помечен @Tool, но отсутствует @AgentToolMeta — тул не будет зарегистрирован", targetClass.getSimpleName(), method.getName());
                    continue;
                }

                String toolName = resolveToolName(tool, method);
                AgentToolMetadata current = new AgentToolMetadata(
                        beanName,
                        method.getName(),
                        toolName,
                        meta.description(),
                        List.of(meta.examples())
                );

                AgentToolMetadata previous = metadata.putIfAbsent(toolName, current);
                if (previous != null) {
                    log.error("Обнаружены два тула с одинаковым именем '{}': {}.{} и {}.{}. Второй будет проигнорирован.",
                            toolName,
                            previous.beanName(), previous.methodName(),
                            targetClass.getSimpleName(), method.getName());
                    continue;
                }

                if (!beanRegistered) {
                    toolBeans.add(bean);
                    beanRegistered = true;
                    log.info("Зарегистрирован тул '{}' (bean='{}', method='{}')", toolName, beanName, method.getName());
                }
            }
        }

        if (toolBeans.isEmpty()) {
            throw new IllegalStateException(NO_TOOL_BEANS_MESSAGE);
        }

        return new AgentToolRegistry(toolBeans, metadata);
    }

    @Bean
    public ToolCallbackProvider toolCallbackProvider(AgentToolRegistry registry) {
        Object[] beans = registry.toolBeans().toArray();
        if (beans.length == 0) {
            throw new IllegalStateException(NO_TOOL_BEANS_MESSAGE);
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(beans)
                .build();
    }

    private String resolveToolName(Tool tool, Method method) {
        if (StringUtils.hasText(tool.name())) {
            return tool.name();
        }
        return method.getName().toLowerCase(Locale.ROOT);
    }
}
