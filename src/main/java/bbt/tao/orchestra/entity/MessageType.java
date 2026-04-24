package bbt.tao.orchestra.entity;

public enum MessageType {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL;

    @Override
    public String toString() {
        return name();
    }
}
