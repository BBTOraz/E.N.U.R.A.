package bbt.tao.orchestra.conf;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestra.openai.realtime")
public class OpenAiRealtimeProperties {

    private String apiKey = "";
    private String baseUrl = "https://api.openai.com";
    private String model = "gpt-realtime";
    private String voice = "marin";
    private String noiseReduction = "near_field";
    private String turnDetectionType = "server_vad";
    private String instructions = """
            Ты голосовой ассистент Евразийского национального университета.
            Отвечай кратко, естественно и разговорно на русском языке.
            Если пользователь говорит на казахском, отвечай на казахском.
            Если тебе не хватает данных, задай один короткий уточняющий вопрос.
            """;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getVoice() {
        return voice;
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public String getNoiseReduction() {
        return noiseReduction;
    }

    public void setNoiseReduction(String noiseReduction) {
        this.noiseReduction = noiseReduction;
    }

    public String getTurnDetectionType() {
        return turnDetectionType;
    }

    public void setTurnDetectionType(String turnDetectionType) {
        this.turnDetectionType = turnDetectionType;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }
}
