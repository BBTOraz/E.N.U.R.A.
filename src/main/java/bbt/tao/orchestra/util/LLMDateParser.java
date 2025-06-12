package bbt.tao.orchestra.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class LLMDateParser {
    private final ChatClient plainChatClient;
    private static final DateTimeFormatter OUTPUT_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public LLMDateParser(@Qualifier("plainChatClient") ChatClient plainChatClient) {
        this.plainChatClient = plainChatClient;
    }

    public Optional<LocalDate> parse(String text, LocalDate baseDate) {

        String baseDateStr = baseDate.format(OUTPUT_FORMATTER);
        String tomorrowStr = baseDate.plusDays(1).format(OUTPUT_FORMATTER);
        String yesterdayStr = baseDate.minusDays(1).format(OUTPUT_FORMATTER);
        String nextMondayStr = baseDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY)).format(OUTPUT_FORMATTER);

        String systemMessageContent = """
            Твоя задача - извлечь из пользовательского запроса ОДНУ КОНКРЕТНУЮ ДАТУ, на которую пользователь хочет получить информацию.
            Верни эту дату в формате ДД.ММ.ГГГГ.
            Текущая дата (сегодня) для контекста: {current_date_context}.
            Если пользователь говорит "завтра", верни {tomorrow_date_context}.
            Если пользователь говорит "понедельник", верни дату ближайшего понедельника, включая сегодняшний день ({next_monday_context}).
            Если пользователь говорит "5 число", это означает 5-е число текущего месяца ({current_month_context}.{current_year_context}).
            Если пользователь говорит "10 января", это означает 10.01.{current_year_context}.
            Если пользователь НЕ УКАЗЫВАЕТ конкретный день или дату (например, "расписание", "какие пары", "на 5 неделю"), ВЕРНИ СТРОКУ "NO_SPECIFIC_DATE".
            Если в запросе есть номер недели, но нет конкретного дня, ВЕРНИ "NO_SPECIFIC_DATE".
            Если не можешь определить дату, ВЕРНИ "NO_SPECIFIC_DATE".
            Ответ должен быть ТОЛЬКО датой в формате ДД.ММ.ГГГГ или строкой "NO_SPECIFIC_DATE". Без дополнительных слов.

            Примеры:
            Запрос: "какое расписание на завтра", Сегодня: {current_date_context} -> Ответ: {tomorrow_date_context}
            Запрос: "расписание на понедельник", Сегодня: {current_date_context} -> Ответ: {next_monday_context}
            Запрос: "что там на 5 число?", Сегодня: {current_date_context} -> Ответ: 05.{current_month_context}.{current_year_context}
            Запрос: "сабақ кестесі ертеңге", Сегодня: {current_date_context} -> Ответ: {tomorrow_date_context}
            Запрос: "расписание на эту неделю" -> Сегодня: {current_date_context} -> Ответ: {current_date_context}
            Запрос: "покажи 3 неделю" -> Ответ: NO_SPECIFIC_DATE
            Запрос: "15 сентября" -> Ответ: 15.09.{current_year_context}
            Запрос: "15.09" -> Ответ: 15.09.{current_year_context}
            Запрос: "что по парам" -> Ответ: NO_SPECIFIC_DATE
            """
                ;

        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemMessageContent);
        Map<String, Object> promptVariables = Map.of(
                "current_date", baseDateStr,
                "tomorrow_date", tomorrowStr,
                "yesterday_date", yesterdayStr,
                "next_monday_date", nextMondayStr,
                "current_year", String.valueOf(baseDate.getYear()),
                "current_month", String.format("%02d", baseDate.getMonthValue())
        );

        Prompt prompt = systemPromptTemplate.create(promptVariables);

        log.info("LlmDateParser - System Prompt: {}", prompt.getInstructions().get(0).getText());
        log.info("LlmDateParser - User Text for LLM: {}", text);

        try {
            ChatResponse response = plainChatClient.prompt() // Используем plainChatClient
                    .system(prompt.getInstructions().get(0).getText())
                    .user("Извлеки дату из этого текста: \"" + text + "\"")
                    .call()
                    .chatResponse();

            assert response != null;
            String llmOutput = response.getResult().getOutput().getText().trim();
            log.info("LlmDateParser - Raw LLM Output: {}", llmOutput);

            if (llmOutput == null || "null".equalsIgnoreCase(llmOutput) || llmOutput.isBlank()) {
                return Optional.empty();
            }
            try {
                LocalDate parsedDate = LocalDate.parse(llmOutput, OUTPUT_FORMATTER);
                return Optional.of(parsedDate);
            } catch (DateTimeParseException e) {
                log.error("LlmDateParser: Failed to parse date from LLM output: '{}'", llmOutput, e);
            }
        } catch (Exception e) {
            log.error("Error calling LLM for date parsing: {}", e.getMessage(), e);
        }
        return Optional.empty();
    }
}
