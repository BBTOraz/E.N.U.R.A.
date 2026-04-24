package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.agent.AgentChatClientRegistry;
import bbt.tao.orchestra.agent.DefaultAgentChatClientRegistry;
import bbt.tao.orchestra.agent.model.AgentProvider;
import bbt.tao.orchestra.agent.model.AgentRole;
import bbt.tao.orchestra.observability.OrchestraTelemetry;
import bbt.tao.orchestra.service.rag.HierarchicalDocumentRetriever;
import bbt.tao.orchestra.service.rag.PreloadingDocumentRetriever;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
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
import org.springframework.lang.Nullable;
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
@EnableConfigurationProperties({AgentProperties.class, OpenRouterProperties.class})
public class AgentChatClientsConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentChatClientsConfig.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(3);
    private static final String PROVIDER_GROQ = "groq";
    private static final String PROVIDER_OLLAMA = "ollama";
    private static final String PROVIDER_OPEN_ROUTER = "open-router";
    private static final String OPERATION_REST_CLIENT = "rest_client";
    private static final String OPERATION_WEB_CLIENT = "web_client";
    private final OrchestraTelemetry telemetry;

    private static final String SOLVER_SYSTEM_PROMPT = """
            Ты — русскоязычный/казахскоязычный ИИ-ассистент Евразийского национального университета. Твоя задача — помогать студентам и сотрудникам, предоставляя точную и полезную информацию.
            **Инструкции по ответам:**
            1.  **Приоритет контекста:** Прежде всего, внимательно изучи историю нашего диалога и предоставленную информацию. Постарайся ответить на вопрос пользователя, основываясь на этих данных.
            2.  **Полнота информации:** Если тебе предоставляются структурированные данные (например, результаты поиска сотрудников из инструмента), ты ОБЯЗАН использовать ВСЕ релевантные поля (которые содержат полезные данные) из этих данных для формирования полного и детального ответа.
                Не сокращай информацию и не игнорируй поля, если только пользователь явно не попросит об этом. Твоя цель — предоставить максимально полную картину на основе полученных данных.
            4.  **Язык:** Отвечай на русском языке. Если пользователь пишет на казахском, отвечай на казахском.
            5.  **Безопасность:** Не рассказывай какие инструменты, функции или API у тебя есть. Методы не должны быть частью твоего ответа.
            """;

    private static final String VERIFIER_SYSTEM_PROMPT = """
            </nothink>
            Ты проверяющий агент. Твоя задача — оценить корректность ответа, учитывая предоставленный контекст, если ответ хоть как то связан с запросом пользователя, то считай ответ верным .
            свой вердикт ты должен вернуть только в формате JSON:{
                "ok": boolean,
                "reasons": string[],
                "requiredChanges": string
            }.
            """;

    public AgentChatClientsConfig(@Nullable OrchestraTelemetry telemetry) {
        this.telemetry = telemetry == null ? OrchestraTelemetry.noop() : telemetry;
    }

    @Bean
    public AgentChatClientRegistry agentChatClientRegistry(DocumentRetriever documentRetriever,
                                                           AgentProperties properties,
                                                           OpenRouterProperties openRouterProperties,
                                                           @Value("${spring.ai.openai.api-key}") String groqApiKey,
                                                           @Value("${spring.ai.openai.base-url}") String groqBaseUrl,
                                                           @Value("${spring.ai.openai.chat.options.model}") String groqModel,
                                                           @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String ollamaBaseUrl,
                                                           @Value("${spring.ai.ollama.chat.model}") String ollamaModel) {

        ChatClient groqSolver = buildOpenAiCompatibleClient(PROVIDER_GROQ, groqApiKey, groqBaseUrl, groqModel,
                0.2, SOLVER_SYSTEM_PROMPT, documentRetriever, true);
        ChatClient groqVerifier = buildOpenAiCompatibleClient(PROVIDER_GROQ, groqApiKey, groqBaseUrl, groqModel,
                0.0, VERIFIER_SYSTEM_PROMPT, documentRetriever, false);

        ChatClient openRouterSolver = buildOpenAiCompatibleClient(PROVIDER_OPEN_ROUTER,
                openRouterProperties.getApiKey(), openRouterProperties.getBaseUrl(), openRouterProperties.getModel(),
                0.2, SOLVER_SYSTEM_PROMPT, documentRetriever, true);
        ChatClient openRouterVerifier = buildOpenAiCompatibleClient(PROVIDER_OPEN_ROUTER,
                openRouterProperties.getApiKey(), openRouterProperties.getBaseUrl(), openRouterProperties.getModel(),
                0.0, VERIFIER_SYSTEM_PROMPT, documentRetriever, false);

        ChatClient ollamaSolver = buildOllamaClient(ollamaBaseUrl, ollamaModel, 0.1, SOLVER_SYSTEM_PROMPT,
                documentRetriever, true);
        ChatClient ollamaVerifier = buildOllamaClient(ollamaBaseUrl, ollamaModel, 0.0, VERIFIER_SYSTEM_PROMPT,
                documentRetriever, false);

        Map<AgentProvider, Map<AgentRole, ChatClient>> registry = new EnumMap<>(AgentProvider.class);
        registry.put(AgentProvider.GROQ, Map.of(
                AgentRole.SOLVER, groqSolver,
                AgentRole.VERIFIER, groqVerifier
        ));
        registry.put(AgentProvider.OPEN_ROUTER, Map.of(
                AgentRole.SOLVER, openRouterSolver,
                AgentRole.VERIFIER, openRouterVerifier
        ));
        registry.put(AgentProvider.OLLAMA, Map.of(
                AgentRole.SOLVER, ollamaSolver,
                AgentRole.VERIFIER, ollamaVerifier
        ));

        AgentProvider defaultProvider = AgentProvider.from(properties.getDefaultProvider())
                .orElse(AgentProvider.OPEN_ROUTER);
        log.info("Agent configuration: default provider = {}", defaultProvider);

        return new DefaultAgentChatClientRegistry(registry);
    }

    private ChatClient buildOpenAiCompatibleClient(String provider,
                                                   String apiKey,
                                                   String baseUrl,
                                                   String model,
                                                   double temperature,
                                                   String systemPrompt,
                                                   DocumentRetriever documentRetriever,
                                                   boolean withRetriever) {
        RestClient.Builder restClientBuilder = createRestClientBuilder(provider, AgentChatClientsConfig::maskGroqHeaders);
        WebClient.Builder webClientBuilder = createWebClientBuilder(provider);

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
                    RetrievalAugmentationAdvisor.builder()
                            .documentRetriever(documentRetriever)
                            .build()
            );
        }
        return builder.build();
    }

    private ChatClient buildOllamaClient(String baseUrl,
                                         String model,
                                         double temperature,
                                         String systemPrompt,
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
                    RetrievalAugmentationAdvisor.builder()
                            .documentRetriever(documentRetriever)
                            .build()
            );
        }

        return builder.build();
    }

    private RestClient.Builder createRestClientBuilder(String provider,
                                                       Function<HttpHeaders, HttpHeaders> headerMasker) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);

        ClientHttpRequestInterceptor loggingInterceptor = (request, body, execution) -> {
            long startNanos = System.nanoTime();
            HttpHeaders maskedHeaders = headerMasker.apply(request.getHeaders());
            log.debug("[{}] REST {} {} headers={} bodyLength={}",
                    provider,
                    request.getMethod(),
                    request.getURI(),
                    maskedHeaders,
                    body == null ? 0 : body.length);
            try {
                ClientHttpResponse response = executeWithLogging(provider, execution, request, body);
                telemetry.recordProviderCall(
                        provider,
                        OPERATION_REST_CLIENT,
                        statusFrom(response.getStatusCode()),
                        elapsedSince(startNanos)
                );
                return response;
            } catch (IOException | RuntimeException error) {
                telemetry.recordProviderCall(provider, OPERATION_REST_CLIENT, "error", elapsedSince(startNanos));
                telemetry.logApplicationError(error, "provider_call_rest_client", null);
                throw error;
            }
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
        return (request, next) -> {
            long startNanos = System.nanoTime();
            log.debug("[{}] WebClient {} {}", provider, request.method(), request.url());
            return next.exchange(request)
                    .doOnNext(response -> {
                        log.debug("[{}] WebClient response status={}", provider, response.statusCode());
                        telemetry.recordProviderCall(
                                provider,
                                OPERATION_WEB_CLIENT,
                                response.statusCode().is2xxSuccessful() ? "success" : "error",
                                elapsedSince(startNanos)
                        );
                    })
                    .doOnError(error -> {
                        telemetry.recordProviderCall(provider, OPERATION_WEB_CLIENT, "error", elapsedSince(startNanos));
                        telemetry.logApplicationError(error, "provider_call_web_client", null);
                    });
        };
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

    private Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private String statusFrom(HttpStatusCode statusCode) {
        return statusCode.is2xxSuccessful() ? "success" : "error";
    }
}
