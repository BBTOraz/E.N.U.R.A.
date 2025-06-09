package bbt.tao.orchestra.conf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.*;


@Slf4j
@Configuration
public class ToolCallBackProviderConfig {

    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            ApplicationContext ctx,
            ConfigurableListableBeanFactory bf
    ) {
        List<Object> toolBeans = new ArrayList<>();

        for (String name : bf.getBeanDefinitionNames()) {
            BeanDefinition bd = bf.getBeanDefinition(name);
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(className);
                for (Method m : clazz.getMethods()) {
                    if (m.isAnnotationPresent(Tool.class)) {
                        Object bean = ctx.getBean(name);
                        toolBeans.add(bean);
                        log.info("Registered @Tool bean: {} (method {})", name, m.getName());
                        break;
                    }
                }
            }
            catch (ClassNotFoundException e) {
               log.error("Failed to load class for bean '{}': {}", name, e.getMessage());
            }
        }

        if (toolBeans.isEmpty()) {
            throw new IllegalStateException("Не найден ни один бин с методами @Tool");
        }

        return MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans.toArray())
                .build();
    }
}