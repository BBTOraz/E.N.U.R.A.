package bbt.tao.orchestra.agent.store;

import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Optional;

public interface AgentScratchpadStore {

    void saveDocuments(String conversationId, List<Document> documents);

    List<Document> loadDocuments(String conversationId);

    void saveDraft(String conversationId, String draft);

    Optional<String> loadDraft(String conversationId);

    void saveSelectedTool(String conversationId, String toolName);

    Optional<String> loadSelectedTool(String conversationId);

    void clear(String conversationId);
}
