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

    private static final Pattern WEEK_NUMBER_PATTERN = Pattern.compile("(?:на\\s+)?(\\d{1,2})\\s+(?:недел[юяиеің]|апта(?:ға|да)?|week)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Map<String, DayOfWeek> RU_DAYS = new HashMap<>();
    private static final Map<String, DayOfWeek> KZ_DAYS = new HashMap<>();
    private static final Map<String, DayOfWeek> EN_DAYS = new HashMap<>();

    static {
        // Русский
        RU_DAYS.put("понедельник", DayOfWeek.MONDAY); RU_DAYS.put("пн", DayOfWeek.MONDAY);
        RU_DAYS.put("вторник", DayOfWeek.TUESDAY); RU_DAYS.put("вт", DayOfWeek.TUESDAY);
        RU_DAYS.put("среду", DayOfWeek.WEDNESDAY); RU_DAYS.put("среда", DayOfWeek.WEDNESDAY); RU_DAYS.put("ср", DayOfWeek.WEDNESDAY);
        RU_DAYS.put("четверг", DayOfWeek.THURSDAY); RU_DAYS.put("чт", DayOfWeek.THURSDAY);
        RU_DAYS.put("пятницу", DayOfWeek.FRIDAY); RU_DAYS.put("пятница", DayOfWeek.FRIDAY); RU_DAYS.put("пт", DayOfWeek.FRIDAY);
        RU_DAYS.put("субботу", DayOfWeek.SATURDAY); RU_DAYS.put("суббота", DayOfWeek.SATURDAY); RU_DAYS.put("сб", DayOfWeek.SATURDAY);
        RU_DAYS.put("воскресенье", DayOfWeek.SUNDAY); RU_DAYS.put("вс", DayOfWeek.SUNDAY);

        // Казахский (примеры, дополнить!)
        KZ_DAYS.put("дүйсенбі", DayOfWeek.MONDAY); KZ_DAYS.put("дуйсенби", DayOfWeek.MONDAY);
        KZ_DAYS.put("сейсенбі", DayOfWeek.TUESDAY); KZ_DAYS.put("сейсенби", DayOfWeek.TUESDAY);
        KZ_DAYS.put("сәрсенбі", DayOfWeek.WEDNESDAY); KZ_DAYS.put("сарсенби", DayOfWeek.WEDNESDAY);
        KZ_DAYS.put("бейсенбі", DayOfWeek.THURSDAY); KZ_DAYS.put("бейсенби", DayOfWeek.THURSDAY);
        KZ_DAYS.put("жұма", DayOfWeek.FRIDAY); KZ_DAYS.put("жума", DayOfWeek.FRIDAY);
        KZ_DAYS.put("сенбі", DayOfWeek.SATURDAY); KZ_DAYS.put("сенби", DayOfWeek.SATURDAY);
        KZ_DAYS.put("жексенбі", DayOfWeek.SUNDAY); KZ_DAYS.put("жексенби", DayOfWeek.SUNDAY);

        // Английский
        EN_DAYS.put("monday", DayOfWeek.MONDAY); EN_DAYS.put("mon", DayOfWeek.MONDAY);
        EN_DAYS.put("tuesday", DayOfWeek.TUESDAY); EN_DAYS.put("tue", DayOfWeek.TUESDAY);
        EN_DAYS.put("wednesday", DayOfWeek.WEDNESDAY); EN_DAYS.put("wed", DayOfWeek.WEDNESDAY);
        EN_DAYS.put("thursday", DayOfWeek.THURSDAY); EN_DAYS.put("thu", DayOfWeek.THURSDAY);
        EN_DAYS.put("friday", DayOfWeek.FRIDAY); EN_DAYS.put("fri", DayOfWeek.FRIDAY);
        EN_DAYS.put("saturday", DayOfWeek.SATURDAY); EN_DAYS.put("sat", DayOfWeek.SATURDAY);
        EN_DAYS.put("sunday", DayOfWeek.SUNDAY); EN_DAYS.put("sun", DayOfWeek.SUNDAY);
    }

    private static final Map<Pattern, Integer> RELATIVE_DAYS_RU = Map.of(
            Pattern.compile("\\bсегодня\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 0,
            Pattern.compile("\\bзавтра\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 1,
            Pattern.compile("\\bпослезавтра\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 2,
            Pattern.compile("\\bвчера\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), -1,
            Pattern.compile("\\bпозавчера\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), -2
    );

    private static final Map<Pattern, Integer> RELATIVE_DAYS_KZ = Map.of(
            Pattern.compile("\\bбүгін\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 0, // бугин
            Pattern.compile("\\bертең\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 1, // ертен
            Pattern.compile("\\bарғы күні\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), 2, // аргы куни (послезавтра)
            Pattern.compile("\\bкеше\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), -1, // кеше
            Pattern.compile("\\bалдыңғы күні\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), -2 // алдынгы куни (позавчера)
    );
    private static final Map<Pattern, Integer> RELATIVE_DAYS_EN = Map.of(
            Pattern.compile("\\btoday\\b", Pattern.CASE_INSENSITIVE), 0,
            Pattern.compile("\\btomorrow\\b", Pattern.CASE_INSENSITIVE), 1,
            Pattern.compile("\\bday after tomorrow\\b", Pattern.CASE_INSENSITIVE), 2,
            Pattern.compile("\\byesterday\\b", Pattern.CASE_INSENSITIVE), -1,
            Pattern.compile("\\bday before yesterday\\b", Pattern.CASE_INSENSITIVE), -2
    );


    public RegexParseOutput process(String text, LocalDate baseDate) {
        String lowerText = text.toLowerCase(Locale.ROOT); // Используем Locale.ROOT для консистентности

        // 1. Проверка на явное указание номера недели
        Matcher weekMatcher = WEEK_NUMBER_PATTERN.matcher(text); // Исходный текст для группы
        if (weekMatcher.find()) {
            try {
                int weekNum = Integer.parseInt(weekMatcher.group(1));
                if (weekNum >= 1 && weekNum <= 15) { // Ограничение на валидные недели
                    log.debug("Regex: Найдена неделя '{}' во фразе '{}'", weekNum, weekMatcher.group(0));
                    return new RegexParseOutput(Optional.empty(), Optional.of(weekNum), weekMatcher.group(0).trim());
                } else {
                    log.warn("Regex: Номер недели '{}' вне диапазона (1-15) во фразе '{}'", weekNum, weekMatcher.group(0));
                    // Если номер недели невалиден, но он был явно указан, это может быть ошибкой,
                    // которую DateResolutionService должен будет обработать.
                    // Возвращаем его, чтобы DRS мог принять решение.
                    return new RegexParseOutput(Optional.empty(), Optional.of(weekNum), weekMatcher.group(0).trim());
                }
            } catch (NumberFormatException e) {
                log.warn("Regex: Ошибка парсинга номера недели из '{}'", weekMatcher.group(1));
            }
        }

        // 2. Проверка относительных дат
        for (Map.Entry<Pattern, Integer> entry : RELATIVE_DAYS_RU.entrySet()) {
            Matcher m = entry.getKey().matcher(lowerText);
            if (m.find()) {
                LocalDate date = baseDate.plusDays(entry.getValue());
                log.debug("Regex: Найдена относительная дата '{}' -> {}", m.group(0), date);
                return new RegexParseOutput(Optional.of(date), Optional.empty(), m.group(0).trim());
            }
        }
        for (Map.Entry<Pattern, Integer> entry : RELATIVE_DAYS_KZ.entrySet()) {
            Matcher m = entry.getKey().matcher(lowerText);
            if (m.find()) {
                LocalDate date = baseDate.plusDays(entry.getValue());
                log.debug("Regex: Найдена относительная дата '{}' -> {}", m.group(0), date);
                return new RegexParseOutput(Optional.of(date), Optional.empty(), m.group(0).trim());
            }
        }
        for (Map.Entry<Pattern, Integer> entry : RELATIVE_DAYS_EN.entrySet()) {
            Matcher m = entry.getKey().matcher(lowerText);
            if (m.find()) {
                LocalDate date = baseDate.plusDays(entry.getValue());
                log.debug("Regex: Найдена относительная дата '{}' -> {}", m.group(0), date);
                return new RegexParseOutput(Optional.of(date), Optional.empty(), m.group(0).trim());
            }
        }

        // 3. Проверка дней недели
        // Сначала русский, т.к. некоторые сокращения могут пересекаться (ср, сб)
        for (Map.Entry<String, DayOfWeek> entry : RU_DAYS.entrySet()) {
            if (lowerText.contains(entry.getKey())) {
                LocalDate date = baseDate.with(TemporalAdjusters.nextOrSame(entry.getValue()));
                log.debug("Regex: Найден день недели '{}' -> {}", entry.getKey(), date);
                return new RegexParseOutput(Optional.of(date), Optional.empty(), entry.getKey());
            }
        }
        for (Map.Entry<String, DayOfWeek> entry : KZ_DAYS.entrySet()) {
            if (lowerText.contains(entry.getKey())) {
                LocalDate date = baseDate.with(TemporalAdjusters.nextOrSame(entry.getValue()));
                log.debug("Regex: Найден день недели '{}' -> {}", entry.getKey(), date);
                return new RegexParseOutput(Optional.of(date), Optional.empty(), entry.getKey());
            }
        }
        for (Map.Entry<String, DayOfWeek> entry : EN_DAYS.entrySet()) {
            if (lowerText.contains(entry.getKey())) {
                LocalDate date = baseDate.with(TemporalAdjusters.nextOrSame(entry.getValue()));
                log.debug("Regex: Найден день недели '{}' -> {}", entry.getKey(), date);
                return new RegexParseOutput(Optional.of(date), Optional.empty(), entry.getKey());
            }
        }

        // Если ничего не найдено
        return new RegexParseOutput(Optional.empty(), Optional.empty(), "");
    }
}
