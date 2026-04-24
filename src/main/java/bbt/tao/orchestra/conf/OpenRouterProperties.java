package bbt.tao.orchestra.conf;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "orchestra.openrouter")
public class OpenRouterProperties {

    private String apiKey = "";
    private String baseUrl = "https://openrouter.ai/api/v1";
    private String model = "qwen/qwen-coder-next";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
