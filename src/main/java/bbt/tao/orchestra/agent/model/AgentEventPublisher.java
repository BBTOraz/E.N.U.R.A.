package bbt.tao.orchestra.agent.model;

@FunctionalInterface
public interface AgentEventPublisher {
    void publish(AgentEvent event);
}
