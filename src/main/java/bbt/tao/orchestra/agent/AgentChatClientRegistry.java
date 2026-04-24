package bbt.tao.orchestra.agent;

import bbt.tao.orchestra.agent.model.AgentProvider;
import bbt.tao.orchestra.agent.model.AgentRole;
import org.springframework.ai.chat.client.ChatClient;

public interface AgentChatClientRegistry {

    ChatClient getClient(AgentProvider provider, AgentRole role);
}
