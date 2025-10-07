package bbt.tao.orchestra.agent;

import bbt.tao.orchestra.agent.model.AgentEvent;
import bbt.tao.orchestra.agent.model.AgentEventPublisher;
import bbt.tao.orchestra.agent.model.AgentRequestContext;
import bbt.tao.orchestra.agent.model.AgentRole;
import bbt.tao.orchestra.agent.model.AgentScratchpad;
import bbt.tao.orchestra.agent.model.AgentStage;
import bbt.tao.orchestra.agent.model.AgentVisibility;
import bbt.tao.orchestra.agent.model.SolverResult;
import bbt.tao.orchestra.classifier.EmbeddingToolClassifier;
import bbt.tao.orchestra.service.rag.PreloadingDocumentRetriever;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

@Service
public class SolverAgent {

    private static final int TOOL_IO_PREVIEW_LIMIT = 512;

    private final AgentChatClientRegistry chatClientRegistry;
    private final EmbeddingToolClassifier classifier;

    public SolverAgent(AgentChatClientRegistry chatClientRegistry,
                       EmbeddingToolClassifier classifier) {
        this.chatClientRegistry = chatClientRegistry;
        this.classifier = classifier;
    }

    public Mono<SolverResult> solve(AgentRequestContext context,
                                    AgentScratchpad scratchpad,
                                    List<Document> ragDocuments,
                                    AgentEventPublisher publisher) {
        return Mono.fromCallable(() -> doSolve(context, scratchpad, ragDocuments, publisher))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private SolverResult doSolve(AgentRequestContext context,
                                 AgentScratchpad scratchpad,
                                 List<Document> ragDocuments,
                                 AgentEventPublisher publisher) {
        scratchpad.setRagDocuments(ragDocuments);

        Optional<ToolCallback> selectedTool = selectTool(context.userMessage());
        selectedTool.ifPresent(scratchpad::setSelectedTool);

        selectedTool.ifPresentOrElse(tool -> publisher.publish(
                        AgentEvent.of(AgentStage.TOOL_SELECTION, AgentVisibility.TRACE,
                                "Tool classifier", "Selected tool " + tool.getToolDefinition().name())
                                .withData(toolSelectionData(tool))),
                () -> publisher.publish(AgentEvent.of(AgentStage.TOOL_SELECTION_SKIPPED, AgentVisibility.TRACE,
                        "Tool classifier", "No tool matched")));

        ChatClient chatClient = chatClientRegistry.getClient(context.solverProvider(), AgentRole.SOLVER);
        Prompt prompt = new Prompt(new UserMessage(context.userMessage()));

        ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt)
                .advisors(advisorSpec -> {
                    advisorSpec.param(ChatMemory.CONVERSATION_ID, context.solverConversationId());
                    advisorSpec.param(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, ragDocuments);
                    advisorSpec.param(PreloadingDocumentRetriever.CONTEXT_KEY, ragDocuments);
                });

        if (selectedTool.isPresent()) {
            ToolCallback tool = selectedTool.get();
            ToolCallback instrumented = instrumentTool(tool, publisher);
            requestSpec = requestSpec.toolCallbacks(instrumented);
        }

        String draft;
        if (context.mode().isStreaming()) {
            draft = requestSpec.stream().content()
                    .doOnNext(chunk -> publisher.publish(AgentEvent.of(AgentStage.SOLVER_TOKEN, AgentVisibility.TRACE,
                            "Solver", chunk)))
                    .collectList()
                    .map(chunks -> String.join("", chunks))
                    .blockOptional()
                    .orElse("");
        } else {
            draft = requestSpec.call().content();
        }
        scratchpad.setDraft(draft);

        publisher.publish(AgentEvent.of(AgentStage.DRAFT_READY, AgentVisibility.TRACE,
                "Draft", "Draft response prepared")
                .withData(Map.of("length", draft == null ? 0 : draft.length())));

        return new SolverResult(draft, false);
    }

    private Optional<ToolCallback> selectTool(String userMessage) {
        Set<ToolCallback> tools = classifier.classifyTools(userMessage);
        return tools.stream().findFirst();
    }

    private Map<String, Object> toolSelectionData(ToolCallback tool) {
        Map<String, Object> data = new HashMap<>();
        data.put("tool", tool.getToolDefinition().name());
        String description = tool.getToolDefinition().description();
        if (description != null && !description.isBlank()) {
            data.put("description", description);
        }
        return data;
    }

    private ToolCallback instrumentTool(ToolCallback delegate, AgentEventPublisher publisher) {
        return new ToolCallback() {
            @Override
            public String call(String request) {
                return executeToolCall(request, null, () -> delegate.call(request), delegate, publisher);
            }

            @Override
            public String call(String request, ToolContext toolContext) {
                return executeToolCall(request, toolContext, () -> delegate.call(request, toolContext), delegate, publisher);
            }

            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return delegate.getToolDefinition();
            }

            @Override
            public org.springframework.ai.tool.metadata.ToolMetadata getToolMetadata() {
                return delegate.getToolMetadata();
            }
        };
    }

    private String executeToolCall(String request,
                                   ToolContext toolContext,
                                   Callable<String> action,
                                   ToolCallback delegate,
                                   AgentEventPublisher publisher) {
        String toolName = delegate.getToolDefinition().name();
        publisher.publish(AgentEvent.of(AgentStage.TOOL_EXECUTION, AgentVisibility.TRACE,
                "Tool execution", "Invoking " + toolName)
                .withData(buildToolEventData(toolName, "started", request, null)));
        try {
            String result = action.call();
            publisher.publish(AgentEvent.of(AgentStage.TOOL_EXECUTION, AgentVisibility.TRACE,
                    "Tool execution", toolName + " completed")
                    .withData(buildToolEventData(toolName, "success", request, result)));
            return result;
        } catch (RuntimeException ex) {
            publisher.publish(AgentEvent.of(AgentStage.TOOL_EXECUTION, AgentVisibility.TRACE,
                    "Tool execution", toolName + " failed")
                    .withData(buildToolEventData(toolName, "error", request, ex.getMessage())));
            throw ex;
        } catch (Exception ex) {
            publisher.publish(AgentEvent.of(AgentStage.TOOL_EXECUTION, AgentVisibility.TRACE,
                    "Tool execution", toolName + " failed")
                    .withData(buildToolEventData(toolName, "error", request, ex.getMessage())));
            throw new RuntimeException("Tool execution failed", ex);
        }
    }

    private Map<String, Object> buildToolEventData(String toolName, String status, String input, String output) {
        Map<String, Object> data = new HashMap<>();
        data.put("tool", toolName);
        data.put("status", status);
        if (input != null) {
            data.put("inputPreview", truncate(input, TOOL_IO_PREVIEW_LIMIT));
        }
        if (output != null) {
            data.put("outputPreview", truncate(output, TOOL_IO_PREVIEW_LIMIT));
        }
        return data;
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "…";
    }
}
