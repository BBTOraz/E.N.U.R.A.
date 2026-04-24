package bbt.tao.orchestra.service.format;

import bbt.tao.orchestra.agent.model.AgentAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentAnswerFormatter {

    private static final Logger log = LoggerFactory.getLogger(AgentAnswerFormatter.class);

    private static final String STRUCTURE_PROMPT = """
            Ты должен вернуть JSON с двумя полями:
            1. fullAnswer — финальная развёрнутая версия ответа пользователю.
            2. summary — краткая выжимка (1-2 предложения) для TTS.
            Используй предоставленный черновик как основу fullAnswer, корректируя фактические и стилистические ошибки при необходимости.
            Summary должен быть самостоятельным, без ссылок на черновик или формулировок вроде \"см. выше\".
            """;

    private final ChatClient formattingClient;
    private final Summarizer summarizer;
    private final BeanOutputConverter<AgentAnswer> outputConverter = new BeanOutputConverter<>(AgentAnswer.class);

    public AgentAnswerFormatter(@Qualifier("plainChatClient") ChatClient formattingClient,
                                Summarizer summarizer) {
        this.formattingClient = formattingClient;
        this.summarizer = summarizer;
    }

    public AgentAnswer format(String userMessage, String draftAnswer) {
        if (!StringUtils.hasText(draftAnswer)) {
            return new AgentAnswer("", "");
        }
        try {
            AgentAnswer answer = formattingClient.prompt()
                    .system(STRUCTURE_PROMPT)
                    .user(buildUserPrompt(userMessage, draftAnswer))
                    .call()
                    .entity(outputConverter);
            if (answer == null) {
                return fallback(draftAnswer);
            }
            String full = StringUtils.hasText(answer.fullAnswer()) ? answer.fullAnswer().trim() : draftAnswer;
            String summary = StringUtils.hasText(answer.summary()) ? answer.summary().trim() : fallbackSummary(full);
            return new AgentAnswer(full, summary);
        } catch (Exception ex) {
            log.warn("Structured output formatting failed: {}", ex.getMessage());
            return fallback(draftAnswer);
        }
    }

    private AgentAnswer fallback(String draftAnswer) {
        String summary = fallbackSummary(draftAnswer);
        return new AgentAnswer(draftAnswer, summary);
    }

    private String fallbackSummary(String draft) {
        try {
            String summary = summarizer.summarize(draft);
            if (StringUtils.hasText(summary)) {
                return summary.trim();
            }
        }
        catch (Exception ex) {
            log.warn("Fallback summarizer failed: {}", ex.getMessage());
        }
        String normalized = draft.strip();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200) + "...";
    }

    private String buildUserPrompt(String userMessage, String draftAnswer) {
        return "Запрос пользователя:\n" + userMessage + "\n\n" +
                "Черновик ответа:\n" + draftAnswer + "\n\n" +
                "Верни JSON без markdown-кодов, соблюдая схему.";
    }
}
