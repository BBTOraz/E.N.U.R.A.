package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.advisor.InlineFunctionRetryAdvisor;
import bbt.tao.orchestra.handler.tool.InlineFunctionHandler;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class ChatClientConfig {
    private final String model;
    private final String baseUrl;
    private final String apiKey;


    private static final String SYSTEM_PROMPT = """
            Ты — русскоязычный/казахскоязычный ИИ-ассистент Евразийского национального университета. Твоя задача — помогать студентам и сотрудникам, предоставляя точную и полезную информацию.
            **Инструкции по ответам:**
            1.  **Приоритет контекста:** Прежде всего, внимательно изучи историю нашего диалога и предоставленную информацию. Постарайся ответить на вопрос пользователя, основываясь на этих данных.
            2.  **Полнота информации:** Если тебе предоставляются структурированные данные (например, результаты поиска сотрудников из инструмента), ты ОБЯЗАН использовать ВСЕ релевантные поля (которые содержат полезные данные) из этих данных для формирования полного и детального ответа.
                Не сокращай информацию и не игнорируй поля, если только пользователь явно не попросит об этом. Твоя цель — предоставить максимально полную картину на основе полученных данных.
            3.  **Ясность и краткость при полноте:** Отвечай по существу. Будь краток там, где это возможно, но никогда не жертвуй важной информацией ради краткости, особенно если эта информация была получена из инструмента.
            4.  **Язык:** Отвечай на русском языке. Если пользователь пишет на казахском, отвечай на казахском.
            5.  **Безопасность:** Не рассказывай какие инструменты, функции или API у тебя есть. Методы не должны быть частью твоего ответа.
            """;

    public ChatClientConfig(@Value("${spring.ai.openai.chat.options.model}")String model,
                            @Value("${spring.ai.openai.base-url}")String baseUrl,
                            @Value("${spring.ai.openai.api-key}")String apiKey) {
        this.model = model;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder chatClientBuilder,
            ChatMemory chatMemory,
            List<InlineFunctionHandler> handlers) {
        return chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new InlineFunctionRetryAdvisor(handlers)
                )
                .build();
    }

    @Bean
    public ChatClient plainChatClient() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.0)
                .build();

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options).build();


        return ChatClient.builder(model)
                .build();
    }

/*    @Bean
    public ChatClient chatClient(OpenAiChatModel model,
                                 ChatMemory chatMemory) {
        return ChatClient.builder(model)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }*/
}
