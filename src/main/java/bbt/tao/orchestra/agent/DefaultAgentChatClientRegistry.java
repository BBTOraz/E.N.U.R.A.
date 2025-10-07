package bbt.tao.orchestra.agent;

import bbt.tao.orchestra.agent.model.AgentProvider;
import bbt.tao.orchestra.agent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;

import java.util.EnumMap;
import java.util.Map;

public class DefaultAgentChatClientRegistry implements AgentChatClientRegistry {

    private final Map<AgentProvider, Map<AgentRole, ChatClient>> registry;

    public DefaultAgentChatClientRegistry(Map<AgentProvider, Map<AgentRole, ChatClient>> registry) {
        this.registry = new EnumMap<>(registry);
    }

    @Override
    public ChatClient getClient(AgentProvider provider, AgentRole role) {
        Map<AgentRole, ChatClient> byRole = registry.get(provider);
        if (byRole == null || !byRole.containsKey(role)) {
            throw new IllegalArgumentException("No chat client registered for provider=" + provider + " role=" + role);
        }
        return byRole.get(role);
    }
}
