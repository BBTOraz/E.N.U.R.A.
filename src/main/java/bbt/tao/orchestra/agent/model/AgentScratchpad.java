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
        String toolName = selectedTool == null ? null : selectedTool.getToolDefinition().name();
        store.saveSelectedTool(context.conversationId(), toolName);
    }

    public List<Document> ragDocuments() {
        return store.loadDocuments(context.conversationId());
    }

    public void setRagDocuments(List<Document> ragDocuments) {
        store.saveDocuments(context.conversationId(), ragDocuments);
    }

    public Optional<String> draft() {
        return store.loadDraft(context.conversationId());
    }

    public void setDraft(String draft) {
        store.saveDraft(context.conversationId(), draft);
    }

    public void clear() {
        selectedTool = null;
        store.clear(context.conversationId());
    }
}
