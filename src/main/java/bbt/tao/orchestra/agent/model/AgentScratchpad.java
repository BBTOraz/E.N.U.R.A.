package bbt.tao.orchestra.agent.model;

import bbt.tao.orchestra.agent.store.AgentScratchpadStore;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AgentScratchpad {

    private final AgentRequestContext context;
    private final AgentScratchpadStore store;
    private ToolCallback selectedTool;
    private List<Document> cachedDocuments = List.of();
    private boolean documentsInitialized = false;
    private boolean toolSelectionInitialized = false;
    private String baseQuery;

    public AgentScratchpad(AgentRequestContext context, AgentScratchpadStore store) {
        this.context = Objects.requireNonNull(context, "context");
        this.store = Objects.requireNonNull(store, "store");
    }

    public AgentRequestContext context() {
        return context;
    }

    public Optional<ToolCallback> selectedTool() {
        return Optional.ofNullable(selectedTool);
    }

    public void setSelectedTool(ToolCallback selectedTool) {
        this.selectedTool = selectedTool;
        this.toolSelectionInitialized = true;
        String toolName = selectedTool == null ? null : selectedTool.getToolDefinition().name();
        store.saveSelectedTool(context.conversationId(), toolName);
    }

    public List<Document> ragDocuments() {
        if (!documentsInitialized) {
            List<Document> loaded = store.loadDocuments(context.conversationId());
            cachedDocuments = loaded == null ? List.of() : List.copyOf(loaded);
            if (!cachedDocuments.isEmpty()) {
                documentsInitialized = true;
            }
        }
        return cachedDocuments;
    }

    public void setRagDocuments(List<Document> ragDocuments) {
        if (documentsInitialized) {
            return;
        }
        cachedDocuments = ragDocuments == null ? List.of() : List.copyOf(ragDocuments);
        documentsInitialized = true;
        store.saveDocuments(context.conversationId(), cachedDocuments);
    }

    public void markNoToolSelected() {
        this.selectedTool = null;
        this.toolSelectionInitialized = true;
        store.saveSelectedTool(context.conversationId(), null);
    }

    public boolean isToolSelectionInitialized() {
        return toolSelectionInitialized;
    }

    public void initializeBaseQuery(String query) {
        if (this.baseQuery == null && query != null && !query.isBlank()) {
            this.baseQuery = query;
        }
    }

    public Optional<String> baseQuery() {
        return Optional.ofNullable(baseQuery);
    }

    public Optional<String> draft() {
        return store.loadDraft(context.conversationId());
    }

    public void setDraft(String draft) {
        store.saveDraft(context.conversationId(), draft);
    }

    public void clear() {
        selectedTool = null;
        cachedDocuments = List.of();
        documentsInitialized = false;
        toolSelectionInitialized = false;
        baseQuery = null;
        store.clear(context.conversationId());
    }
}
