package bbt.tao.orchestra.dto;

public record TitleGenerationRequest(String message) {
    public boolean isValid() {
        return message != null && !message.trim().isEmpty();
    }
}
