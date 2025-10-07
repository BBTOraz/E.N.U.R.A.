package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.agent.AgentChatClientRegistry;
import bbt.tao.orchestra.agent.DefaultAgentChatClientRegistry;
import bbt.tao.orchestra.agent.model.AgentProvider;
import bbt.tao.orchestra.agent.model.AgentRole;
import bbt.tao.orchestra.service.rag.HierarchicalDocumentRetriever;
import bbt.tao.orchestra.service.rag.PreloadingDocumentRetriever;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class AgentChatClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentChatClientsConfig.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(3);
    private static final String PROVIDER_GROQ = "groq";
    private static final String PROVIDER_OLLAMA = "ollama";

    private static final String SOLVER_SYSTEM_PROMPT = """
            You are the solver agent. Provide accurate and helpful answers,
            reuse RAG context when available, and ask clarifying questions if the input is ambiguous.
            Use tools only for their intended purpose and keep responses concise.
            """;

    private static final String VERIFIER_SYSTEM_PROMPT = """
            You are the verifier agent. Validate completeness, factual accuracy, formatting and relevance.
            Return ONLY JSON conforming to {
  "ok": boolean,
  "reasons": string[],
  "requiredChanges": string
}.
            """;

    @Bean
    public AgentChatClientRegistry agentChatClientRegistry(ChatMemory chatMemory,
                                                           DocumentRetriever documentRetriever,
                                                           AgentProperties properties,
                                                           @Value("${spring.ai.openai.api-key}") String groqApiKey,
                                                           @Value("${spring.ai.openai.base-url}") String groqBaseUrl,
                                                           @Value("${spring.ai.openai.chat.options.model}") String groqModel,
                                                           @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                                                           @Value("${spring.ai.ollama.chat.model}") String ollamaModel) {

        ChatClient groqSolver = buildGroqClient(groqApiKey, groqBaseUrl, groqModel, 0.2, SOLVER_SYSTEM_PROMPT,
                chatMemory, documentRetriever, true);
        ChatClient groqVerifier = buildGroqClient(groqApiKey, groqBaseUrl, groqModel, 0.0, VERIFIER_SYSTEM_PROMPT,
                chatMemory, documentRetriever, false);

        ChatClient ollamaSolver = buildOllamaClient(ollamaBaseUrl, ollamaModel, 0.1, SOLVER_SYSTEM_PROMPT,
                chatMemory, documentRetriever, true);
        ChatClient ollamaVerifier = buildOllamaClient(ollamaBaseUrl, ollamaModel, 0.0, VERIFIER_SYSTEM_PROMPT,
                chatMemory, documentRetriever, false);

        Map<AgentProvider, Map<AgentRole, ChatClient>> registry = new EnumMap<>(AgentProvider.class);
        registry.put(AgentProvider.GROQ, Map.of(
                AgentRole.SOLVER, groqSolver,
                AgentRole.VERIFIER, groqVerifier
        ));
        registry.put(AgentProvider.OLLAMA, Map.of(
                AgentRole.SOLVER, ollamaSolver,
                AgentRole.VERIFIER, ollamaVerifier
        ));

        AgentProvider defaultProvider = AgentProvider.from(properties.getDefaultProvider())
                .orElse(AgentProvider.GROQ);
        if (!registry.containsKey(defaultProvider)) {
            log.warn("Agent configuration: default provider '{}' is not available, falling back to GROQ",
                    properties.getDefaultProvider());
        }

        return new DefaultAgentChatClientRegistry(registry);
    }

    private ChatClient buildGroqClient(String apiKey,
                                       String baseUrl,
                                       String model,
                                       double temperature,
                                       String systemPrompt,
                                       ChatMemory chatMemory,
                                       DocumentRetriever documentRetriever,
                                       boolean withRetriever) {
        RestClient.Builder restClientBuilder = createRestClientBuilder(PROVIDER_GROQ, AgentChatClientsConfig::maskGroqHeaders);
        WebClient.Builder webClientBuilder = createWebClientBuilder(PROVIDER_GROQ);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        OpenAiChatModel modelInstance = OpenAiChatModel.builder()
                .openAiApi(openAiApi)
                .defaultOptions(options)
                .build();

        ChatClient.Builder builder = ChatClient.builder(modelInstance)
                .defaultSystem(systemPrompt);

        if (withRetriever) {
            builder.defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    RetrievalAugmentationAdvisor.builder()
                            .documentRetriever(documentRetriever)
                            .build()
            );
        } else {
            builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }

        return builder.build();
    }

    private ChatClient buildOllamaClient(String baseUrl,
                                         String model,
                                         double temperature,
                                         String systemPrompt,
                                         ChatMemory chatMemory,
                                         DocumentRetriever documentRetriever,
                                         boolean withRetriever) {
        RestClient.Builder restClientBuilder = createRestClientBuilder(PROVIDER_OLLAMA, Function.identity());
        WebClient.Builder webClientBuilder = createWebClientBuilder(PROVIDER_OLLAMA);

        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();

        OllamaOptions options = OllamaOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        OllamaChatModel modelInstance = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(options)
                .build();

        ChatClient.Builder builder = ChatClient.builder(modelInstance)
                .defaultSystem(systemPrompt);

        if (withRetriever) {
            builder.defaultAdvisors(
                    MessageChatMemoryAdvisor.builder(chatMemory).build(),
                    RetrievalAugmentationAdvisor.builder()
                            .documentRetriever(documentRetriever)
                            .build()
            );
        } else {
            builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build());
        }

        return builder.build();
    }

    private RestClient.Builder createRestClientBuilder(String provider,
                                                       Function<HttpHeaders, HttpHeaders> headerMasker) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        ClientHttpRequestInterceptor loggingInterceptor = (request, body, execution) -> {
            HttpHeaders maskedHeaders = headerMasker.apply(request.getHeaders());
            log.debug("[{}] REST {} {} headers={} bodyLength={}",
                    provider,
                    request.getMethod(),
                    request.getURI(),
                    maskedHeaders,
                    body == null ? 0 : body.length);
            ClientHttpResponse response = executeWithLogging(provider, execution, request, body);
            return response;
        };

        return RestClient.builder()
                .requestFactory(factory)
                .requestInterceptor(loggingInterceptor);
    }

    private ClientHttpResponse executeWithLogging(String provider,
                                                  ClientHttpRequestExecution execution,
                                                  org.springframework.http.HttpRequest request,
                                                  byte[] body) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        HttpStatusCode status = response.getStatusCode();
        log.debug("[{}] REST response {} {}", provider, status.value(), status.toString());
        return response;
    }

    private WebClient.Builder createWebClientBuilder(String provider) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .responseTimeout(READ_TIMEOUT)
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler((int) READ_TIMEOUT.getSeconds()))
                        .addHandlerLast(new WriteTimeoutHandler((int) READ_TIMEOUT.getSeconds())));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(loggingFilter(provider));
    }

    private ExchangeFilterFunction loggingFilter(String provider) {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
                    log.debug("[{}] WebClient {} {}", provider, request.method(), request.url());
                    return Mono.just(request);
                })
                .andThen(ExchangeFilterFunction.ofResponseProcessor(response -> {
                    log.debug("[{}] WebClient response status={}", provider, response.statusCode());
                    return Mono.just(response);
                }));
    }

    private static HttpHeaders maskGroqHeaders(HttpHeaders headers) {
        HttpHeaders masked = new HttpHeaders();
        headers.forEach((key, values) -> {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(key)) {
                masked.put(key, values.stream().map(AgentChatClientsConfig::mask).toList());
            } else {
                masked.put(key, values);
            }
        });
        return masked;
    }

    private static String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (value.length() <= 8) {
            return "****";
        }
        return value.substring(0, 4) + "****" + value.substring(value.length() - 4);
    }

    
    @Bean(name = "solverAgentChatClient")
    public ChatClient solverAgentChatClientAdapter(AgentChatClientRegistry registry, AgentProperties properties) {
        AgentProvider provider = AgentProvider.from(properties.getDefaultProvider()).orElse(AgentProvider.GROQ);
        return registry.getClient(provider, AgentRole.SOLVER);
    }

    @Bean(name = "verifierAgentChatClient")
    public ChatClient verifierAgentChatClientAdapter(AgentChatClientRegistry registry, AgentProperties properties) {
        AgentProvider provider = AgentProvider.from(properties.getDefaultProvider()).orElse(AgentProvider.GROQ);
        return registry.getClient(provider, AgentRole.VERIFIER);
    }

    @Bean
    @Primary
    public DocumentRetriever preloadingDocumentRetriever(HierarchicalDocumentRetriever hierarchicalDocumentRetriever) {
        return new PreloadingDocumentRetriever(hierarchicalDocumentRetriever);
    }
}
