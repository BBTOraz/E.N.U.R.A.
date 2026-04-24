package bbt.tao.orchestra.dto.voice;

public record RealtimeSessionResponse(
        String clientSecret,
        Long expiresAt,
        String model,
        String voice
) {
}
