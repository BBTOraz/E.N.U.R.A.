package bbt.tao.orchestra.service.format;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class LlmSummarizer implements Summarizer {

    private final ChatClient client;

    public LlmSummarizer(@Qualifier("plainChatClient") ChatClient client) {
        this.client = client;
    }

    @Override
    public String summarize(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return "";
        }
        String system = """
                Ты - ассистент, который помогает пользователю кратко резюмировать длинные тексты.
                Резюмируй текст, сохраняя ключевые мысли и идеи, но делая его максимально кратким.
                Кроме запятой и точки, не используй никаких символов.
                Твой текст нужен для синтеза речи, поэтому не используй сложные конструкции.
                """;
        return client
                .prompt()
                .system(system)
                .user(fullText)
                .call()
                .content();
    }
}

