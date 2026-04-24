package bbt.tao.orchestra.conf;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OpenAiRealtimeProperties.class)
public class OpenAiRealtimeConfig {
}
