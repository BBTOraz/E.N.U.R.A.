package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.service.rag.HierarchicalDocumentRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentChatClientsConfig {

    private static final String SOLVER_SYSTEM_PROMPT = """
            Ты — агент‑решатель. Твоя задача — давать точные и полезные ответы,
            при необходимости используя инструменты и знания из контекста RAG. Если данных недостаточно — попроси уточнить.
            Если вызываешь инструменты, используй их только по назначению. Пиши ясно и по делу.
            """;

    private static final String VERIFIER_SYSTEM_PROMPT = """
            Ты — агент‑проверяющий. Проверь проект ответа на полноту, корректность, факты, формат и релевантность запросу.
            Верни ТОЛЬКО JSON строго по схеме: {\n  \"ok\": boolean,\n  \"reasons\": string[],\n  \"requiredChanges\": string\n}\nБез какого‑либо другого текста.
            """;

    @Bean(name = "solverAgentChatClient")
    public ChatClient solverAgentChatClient(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String modelName,
            ChatMemory chatMemory,
            HierarchicalDocumentRetriever hierarchicalDocumentRetriever
    ) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.2)
                .build();

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        return ChatClient.builder(model)
                .defaultSystem(SOLVER_SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        RetrievalAugmentationAdvisor.builder()
                                .documentRetriever(hierarchicalDocumentRetriever)
                                .build()
                )
                .build();
    }

    @Bean(name = "verifierAgentChatClient")
    public ChatClient verifierAgentChatClient(
            @Value("${spring.ai.openai.api-key}") String apiKey,
            @Value("${spring.ai.openai.base-url}") String baseUrl,
            @Value("${spring.ai.openai.chat.options.model}") String modelName
    ) {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(0.0)
                .build();

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        return ChatClient.builder(model)
                .defaultSystem(VERIFIER_SYSTEM_PROMPT)
                .build();
    }
}

