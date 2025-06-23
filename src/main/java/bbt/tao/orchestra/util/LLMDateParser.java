package bbt.tao.orchestra.util;

import bbt.tao.orchestra.dto.ExtractedDateResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.util.Map.entry;

@Component
@Slf4j
public class LLMDateParser {
    private final ChatClient plainChatClient;
    private static final DateTimeFormatter DD_MM_YYYY_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Locale RUSSIAN_LOCALE = new Locale("ru");
    private final BeanOutputConverter<ExtractedDateResponse> outputParser;

    public LLMDateParser(@Qualifier("plainChatClient") ChatClient plainChatClient) {
        this.plainChatClient = plainChatClient;
        this.outputParser = new BeanOutputConverter<>(ExtractedDateResponse.class);
    }

    public Optional<LocalDate> extractDate(String userInput, LocalDate baseDate) {
        String baseDateStr = baseDate.format(DD_MM_YYYY_FORMATTER);
        String currentDayOfWeekStr = baseDate.getDayOfWeek().getDisplayName(TextStyle.FULL_STANDALONE, RUSSIAN_LOCALE);
        String tomorrowStr = baseDate.plusDays(1).format(DD_MM_YYYY_FORMATTER);
        String dayAfterTomorrowStr = baseDate.plusDays(2).format(DD_MM_YYYY_FORMATTER);
        String yesterdayStr = baseDate.minusDays(1).format(DD_MM_YYYY_FORMATTER);

        LocalDate mondayThisWeek = baseDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate mondayNextWeek = baseDate.plusWeeks(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); // Понедельник следующей недели

        String thisMondayStr = mondayThisWeek.format(DD_MM_YYYY_FORMATTER);
        String nextMondayStr = mondayNextWeek.format(DD_MM_YYYY_FORMATTER);
        String nextWeekSameDayStr = baseDate.plusWeeks(1).format(DD_MM_YYYY_FORMATTER);
        String currentYearStr = String.valueOf(baseDate.getYear());
        String currentMonthStr = String.format("%02d", baseDate.getMonthValue());
        String nextYearStr = String.valueOf(baseDate.getYear() + 1);
        LocalDate nextMonthDate = baseDate.plusMonths(1).withDayOfMonth(1);
        String nextMonthNumStr = String.format("%02d", nextMonthDate.getMonthValue());
        String yearOfNextMonthStr = String.valueOf(nextMonthDate.getYear());

        String systemMessageContent = """
            Твоя задача - извлечь из запроса пользователя КОНКРЕТНУЮ ДАТУ и вернуть её в формате ДД.ММ.ГГГГ.
            Старайся ВСЕГДА вернуть дату, если это логически возможно.
            Если из запроса СОВСЕМ НЕВОЗМОЖНО извлечь или вывести какую-либо дату (например, запрос "привет" или "расскажи анекдот"), ТОЛЬКО ТОГДА установи 'is_date_not_found: true'.

            КОНТЕКСТ ДЛЯ ТЕБЯ (ИСПОЛЬЗУЙ ЭТИ ДАТЫ, ЕСЛИ ОНИ ПОДХОДЯТ):
            - Сегодня: {current_date_context} (это {current_day_of_week_context}).
            - Завтра: {tomorrow_date_context}.
            - Послезавтра: {day_after_tomorrow_context}.
            - Вчера: {yesterday_date_context}.
            - Понедельник ЭТОЙ недели: {this_monday_context}.
            - ПОНЕДЕЛЬНИК СЛЕДУЮЩЕЙ НЕДЕЛИ: {next_monday_context}.
            - Этот же день на СЛЕДУЮЩЕЙ неделе: {next_week_same_day_context}.
            - Текущий год: {current_year_context}. Текущий месяц (числом): {current_month_context}. Следующий год: {next_year_context}.

            ПРАВИЛА ИНТЕРПРЕТАЦИИ ДЛЯ ИЗВЛЕЧЕНИЯ КОНКРЕТНОЙ ДАТЫ:
            1.  "СЕГОДНЯ", "ЗАВТРА", "ВЧЕРА", "ПОСЛЕЗАВТРА": Используй соответствующую дату из КОНТЕКСТА.
            2.  ДЕНЬ НЕДЕЛИ (например, "вторник", "сейсенбіде"):
                *   Если просто "вторник": это БЛИЖАЙШИЙ будущий вторник. Если сегодня понедельник, то "вторник" - это завтра. Если сегодня среда, то "вторник" - это вторник СЛЕДУЮЩЕЙ недели. Если сегодня вторник, то "вторник" - это сегодня.
                *   "ВО ВТОРНИК СЛЕДУЮЩЕЙ НЕДЕЛИ": Вычисли дату вторника на неделе ПОСЛЕ текущей ({current_date_context}).
                *   "В ПРОШЛЫЙ ВТОРНИК": Вычисли дату вторника на неделе ДО текущей.
            3.  "НА СЛЕДУЮЩЕЙ НЕДЕЛЕ" (без указания дня): Верни дату ПОНЕДЕЛЬНИКА СЛЕДУЮЩЕЙ НЕДЕЛИ ({next_monday_context}).
            4.  "НА ЭТОЙ НЕДЕЛЕ" (без указания дня): Верни дату ПОНЕДЕЛЬНИКА ТЕКУЩЕЙ НЕДЕЛИ ({this_monday_context}). Если сегодня понедельник, верни сегодняшнюю дату.
            5.  "X ЧИСЛО" (например, "5 число"): Это X-е число ТЕКУЩЕГО месяца. Если X-е число УЖЕ ПРОШЛО в текущем месяце, то это X-е число СЛЕДУЮЩЕГО месяца.
            6.  "X МЕСЯЦ" (например, "10 января"): Это X-е число указанного месяца в ТЕКУЩЕМ году. Если указанный месяц УЖЕ ПРОШЕЛ в текущем году, то это X-е число этого месяца в СЛЕДУЮЩЕМ году.
            7.  "ДД.ММ": Это ДД.ММ в ТЕКУЩЕМ году.
            8.  СЛОЖНЫЕ ФРАЗЫ: Вычисляй дату ("через 2 дня" -> {day_after_tomorrow_context}; "в конце апреля" -> 30.04.{current_year_context}).
            9.  ЕСЛИ ЗАПРОС СОДЕРЖИТ ТОЛЬКО НОМЕР УЧЕБНОЙ НЕДЕЛИ (например, "расписание на 5 неделю") и НЕТ УКАЗАНИЯ НА КОНКРЕТНЫЙ ДЕНЬ: В этом случае установи 'is_date_not_found: true', так как нужна конкретная дата календаря, а не номер недели.
            10. ЕСЛИ ЗАПРОС ВООБЩЕ НЕ СОДЕРЖИТ ВРЕМЕННЫХ УКАЗАТЕЛЕЙ (например, "какое расписание", "что по парам"): Верни СЕГОДНЯШНЮЮ ДАТУ ({current_date_context}).
            11. ЕСЛИ ЗАПРОС АБСОЛЮТНО НЕ РЕЛЕВАНТЕН ДАТАМ (например, "привет"): ТОЛЬКО ТОГДА установи 'is_date_not_found: true'.

            ТРЕБОВАНИЕ К ОТВЕТУ: Только JSON с полями 'extracted_date' (в формате ДД.ММ.ГГГГ или null, если is_date_not_found=true) и 'is_date_not_found' (true или false).

            ПРИМЕРЫ (Предположим, Сегодня: {current_date_context} ({current_day_of_week_context})):
            - Запрос: "расписание на сегодня" -> {{"extracted_date": "{current_date_context}", "is_date_not_found": false}}
            - Запрос: "что по парам завтра?" (сегодня 15.09.2024 Вс) -> {{"extracted_date": "16.09.2024", "is_date_not_found": false}}
            - Запрос: "вторник" (сегодня 09.09.2024 Пн) -> {{"extracted_date": "10.09.2024", "is_date_not_found": false}}
            - Запрос: "вторник" (сегодня 11.09.2024 Ср) -> {{"extracted_date": "17.09.2024", "is_date_not_found": false}} (вторник следующей недели)
            - Запрос: "в следующий понедельник" (сегодня 09.09.2024 Пн) -> {{"extracted_date": "{next_monday_context}", "is_date_not_found": false}}
            - Запрос: "расписание на вторник следующей недели" (сегодня 09.09.2024 Пн) -> {{"extracted_date": "17.09.2024", "is_date_not_found": false}}
            - Запрос: "расписание на следующей неделе" (сегодня 09.09.2024 Пн) -> {{"extracted_date": "{next_monday_context}", "is_date_not_found": false}} (понедельник следующей недели)
            - Запрос: "расписание на эту неделю" (сегодня 11.09.2024 Ср) -> {{"extracted_date": "{this_monday_context}", "is_date_not_found": false}} (понедельник этой недели)
            - Запрос: "какие пары" (сегодня 11.09.2024 Ср) -> {{"extracted_date": "{current_date_context}", "is_date_not_found": false}} (сегодняшняя дата)
            - Запрос: "покажи 3 учебную неделю" -> {{"extracted_date": null, "is_date_not_found": true}}
            - Запрос: "привет" -> {{"extracted_date": null, "is_date_not_found": true}}

            {format_instructions}
            """;

        Map<String, Object> promptVariables = Map.ofEntries(
                Map.entry("current_date_context", baseDateStr),
                Map.entry("current_day_of_week_context", currentDayOfWeekStr),
                Map.entry("tomorrow_date_context", tomorrowStr),
                Map.entry("day_after_tomorrow_context", dayAfterTomorrowStr),
                Map.entry("yesterday_date_context", yesterdayStr),
                Map.entry("this_monday_context", thisMondayStr),
                Map.entry("next_monday_context", nextMondayStr),
                Map.entry("next_week_same_day_context", nextWeekSameDayStr),
                Map.entry("current_year_context", currentYearStr),
                Map.entry("current_month_context", currentMonthStr),
                Map.entry("next_year_context", nextYearStr),
                Map.entry("next_month_num", nextMonthNumStr),
                Map.entry("year_of_next_month", yearOfNextMonthStr),
                Map.entry("format_instructions", outputParser.getFormat())
        );

        SystemPromptTemplate systemPromptTemplate = new SystemPromptTemplate(systemMessageContent);
        Prompt prompt;
        try {
            prompt = systemPromptTemplate.create(promptVariables);
        } catch (IllegalStateException e) {
            log.error("LlmDateExtractor: КРИТИЧЕСКАЯ ОШИБКА при создании Prompt. Переменные шаблона не совпадают! Ошибка: {}", e.getMessage());
            log.error("LlmDateExtractor: Проверьте плейсхолдеры в systemMessageContent ({}) и ключи в promptVariables ({}).",
                    Pattern.compile("\\{([^}]+)\\}").matcher(systemMessageContent).results().map(mr -> mr.group(1)).collect(Collectors.toSet()),
                    promptVariables.keySet());
            throw e;
        }

        log.info("LlmDateExtractor - System Prompt для LLM (первые 500 символов):\n{}", prompt.getInstructions().get(0).getText().substring(0, Math.min(prompt.getInstructions().get(0).getText().length(), 500)));
        log.info("LlmDateExtractor - User Input для LLM: {}", userInput);

        //todo не совсем уверен в правильности использование реактивного подхода здесь, но в целом работает
        try {
           ChatResponse callResponseSpec =
                    Mono.fromCallable(()-> {
                      return plainChatClient.prompt()
                                        .system(prompt.getInstructions().get(0).getText())
                                        .user(userInput)
                                        .call()
                                .chatResponse();
                    }).subscribeOn(Schedulers.boundedElastic()).toFuture().get();



            ExtractedDateResponse extractedResponse = outputParser.convert(callResponseSpec.getResult().getOutput().getText()); // Используем getText() если это актуально для вашей версии
            log.info("LlmDateExtractor - Parsed LLM Output: {}", extractedResponse);

            if (extractedResponse != null && !extractedResponse.isDateNotFound() && extractedResponse.extractedDate() != null) {
                try {
                    LocalDate parsedDate = LocalDate.parse(extractedResponse.extractedDate(), DD_MM_YYYY_FORMATTER);
                    return Optional.of(parsedDate);
                } catch (DateTimeParseException e) {
                    log.error("LlmDateExtractor: Не удалось распарсить дату из поля 'extracted_date': '{}'. Ошибка: {}", extractedResponse.extractedDate(), e.getMessage());
                }
            } else {
                log.info("LlmDateExtractor: LLM указала, что дата не найдена (is_date_not_found=true), или поле даты пустое.");
            }
        } catch (Exception e) {
            log.error("LlmDateExtractor: Ошибка при вызове LLM или парсинге ответа: {}", e.getMessage(), e);
        }
        return Optional.empty();

    }
}
