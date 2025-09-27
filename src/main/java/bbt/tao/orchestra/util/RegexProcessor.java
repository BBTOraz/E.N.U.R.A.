package bbt.tao.orchestra.util;

import bbt.tao.orchestra.dto.RegexParseOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Slf4j
public class RegexProcessor {

    private static final Pattern WEEK_NUMBER_PATTERN = Pattern.compile(
            "(?:на\\s+|of\\s+)?(\\d{1,2})\\s*(?:-|\\b)?\\s*(?:недел[юяиеің]|апта(?:ға|да)?|week)", // Удалены тамильские части
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    private static final Map<Pattern, Integer> SIMPLE_RELATIVE_DAYS = Map.ofEntries(
            // Русский
            Map.entry(Pattern.compile("^сегодня$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 0),
            Map.entry(Pattern.compile("^завтра$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 1),
            Map.entry(Pattern.compile("^послезавтра$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 2),
            Map.entry(Pattern.compile("^вчера$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), -1),
            // Казахский
            Map.entry(Pattern.compile("^бүгін$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 0),
            Map.entry(Pattern.compile("^ертең$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 1),
            Map.entry(Pattern.compile("^арғы\\s+күні$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 2),
            Map.entry(Pattern.compile("^кеше$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), -1),
            // Английский
            Map.entry(Pattern.compile("^today$", Pattern.CASE_INSENSITIVE), 0),
            Map.entry(Pattern.compile("^tomorrow$", Pattern.CASE_INSENSITIVE), 1),
            Map.entry(Pattern.compile("^day\\s+after\\s+tomorrow$", Pattern.CASE_INSENSITIVE), 2),
            Map.entry(Pattern.compile("^yesterday$", Pattern.CASE_INSENSITIVE), -1)
    );

    public RegexParseOutput process(String text, LocalDate baseDate) {
        String normalizedText = text.trim().toLowerCase(Locale.ROOT);

        // Эвристика: распознать относительные дни внутри фразы и при склеенных словах
        String squeezed = normalizedText.replaceAll("\\s+", "");
        if (containsWord(normalizedText, "сегодня") || squeezed.contains("сегодня")) {
            LocalDate date = baseDate.plusDays(0);
            log.debug("Regex: распознана относительная дата 'сегодня' -> {}", date);
            return new RegexParseOutput(Optional.of(date), Optional.empty(), "сегодня");
        }
        if (containsWord(normalizedText, "завтра") || squeezed.contains("завтра")) {
            LocalDate date = baseDate.plusDays(1);
            log.debug("Regex: распознана относительная дата 'завтра' -> {}", date);
            return new RegexParseOutput(Optional.of(date), Optional.empty(), "завтра");
        }
        if (containsWord(normalizedText, "послезавтра") || squeezed.contains("послезавтра")) {
            LocalDate date = baseDate.plusDays(2);
            log.debug("Regex: распознана относительная дата 'послезавтра' -> {}", date);
            return new RegexParseOutput(Optional.of(date), Optional.empty(), "послезавтра");
        }
        if (containsWord(normalizedText, "вчера") || squeezed.contains("вчера")) {
            LocalDate date = baseDate.plusDays(-1);
            log.debug("Regex: распознана относительная дата 'вчера' -> {}", date);
            return new RegexParseOutput(Optional.of(date), Optional.empty(), "вчера");
        }
        // English fallbacks
        if (containsWord(normalizedText, "today") || squeezed.contains("today")) {
            return new RegexParseOutput(Optional.of(baseDate), Optional.empty(), "today");
        }
        if (containsWord(normalizedText, "tomorrow") || squeezed.contains("tomorrow")) {
            return new RegexParseOutput(Optional.of(baseDate.plusDays(1)), Optional.empty(), "tomorrow");
        }
        if (containsWord(normalizedText, "day after tomorrow") || squeezed.contains("dayaftertomorrow")) {
            return new RegexParseOutput(Optional.of(baseDate.plusDays(2)), Optional.empty(), "day after tomorrow");
        }
        if (containsWord(normalizedText, "yesterday") || squeezed.contains("yesterday")) {
            return new RegexParseOutput(Optional.of(baseDate.minusDays(1)), Optional.empty(), "yesterday");
        }

        Matcher weekMatcher = WEEK_NUMBER_PATTERN.matcher(text);
        if (weekMatcher.find()) {
            try {
                int weekNum = Integer.parseInt(weekMatcher.group(1));
                log.debug("Regex: Найдена неделя '{}' во фразе '{}'", weekNum, weekMatcher.group(0).trim());
                return new RegexParseOutput(Optional.empty(), Optional.of(weekNum), weekMatcher.group(0).trim());
            } catch (NumberFormatException e) {
                log.warn("Regex: Ошибка парсинга номера недели из '{}' в тексте '{}'", weekMatcher.group(1), text);
            }
        }

        for (Map.Entry<Pattern, Integer> entry : SIMPLE_RELATIVE_DAYS.entrySet()) {
            if (entry.getKey().matcher(normalizedText).find()) {
                LocalDate date = baseDate.plusDays(entry.getValue());
                log.debug("Regex: Найдена простая относительная дата '{}' -> {}", normalizedText, date);
                return new RegexParseOutput(Optional.of(date), Optional.empty(), normalizedText);
            }
        }

        return new RegexParseOutput(Optional.empty(), Optional.empty(), "");
    }

    private boolean containsWord(String text, String word) {
        String escaped = Pattern.quote(word);
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}_])" + escaped + "(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return p.matcher(text).find();
    }
}
