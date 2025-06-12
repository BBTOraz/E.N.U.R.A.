package bbt.tao.orchestra.service;


import bbt.tao.orchestra.dto.DateResolutionDetails;
import bbt.tao.orchestra.dto.RegexParseOutput;
import bbt.tao.orchestra.dto.ResolvedQueryType;
import bbt.tao.orchestra.dto.enu.platonus.PlatonusScheduleRequest;
import bbt.tao.orchestra.util.AcademicTime;
import bbt.tao.orchestra.util.LLMDateParser;
import bbt.tao.orchestra.util.RegexProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
public class DateResolutionService {
    private final RegexProcessor regexProcessor;
    private final LLMDateParser llmDateExtractor;
    private final AcademicTime academicTimeOracle;
    private static final DateTimeFormatter USER_FRIENDLY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("ru"));

    public DateResolutionService(RegexProcessor regexProcessor, LLMDateParser llmDateExtractor, AcademicTime academicTimeOracle) {
        this.regexProcessor = regexProcessor;
        this.llmDateExtractor = llmDateExtractor;
        this.academicTimeOracle = academicTimeOracle;
    }

    public DateResolutionDetails resolve(String userInput) {
        LocalDate today = LocalDate.now();
        Optional<LocalDate> specificDate = Optional.empty();
        Optional<Integer> explicitWeekNumber = Optional.empty();
        String logPhrase = userInput;

        RegexParseOutput regexOutput = regexProcessor.process(userInput, today);
        if (regexOutput.specificDate().isPresent()) {
            specificDate = regexOutput.specificDate();
            logPhrase = regexOutput.matchedPhraseForLog();
        } else if (regexOutput.weekNumber().isPresent()) {
            explicitWeekNumber = regexOutput.weekNumber();
            logPhrase = regexOutput.matchedPhraseForLog();
        }

        if (specificDate.isEmpty() && explicitWeekNumber.isEmpty()) {
            specificDate = llmDateExtractor.parse(userInput, today);
            if (specificDate.isPresent()) {
                logPhrase = "дата извлечена LLM";
            }
        }

        PlatonusScheduleRequest apiParams;
        ResolvedQueryType finalQueryType;
        String summary;
        Optional<DayOfWeek> dayFilter = Optional.empty();

        if (specificDate.isPresent()) {
            LocalDate resolvedDate = specificDate.get();
            apiParams = academicTimeOracle.getTermAndWeek(resolvedDate);
            if (apiParams == null) {
                finalQueryType = ResolvedQueryType.ERROR_DATE_OUT_OF_SEMESTER;
                summary = "Ошибка: дата " + resolvedDate.format(USER_FRIENDLY_DATE_FORMATTER) + " не попадает в известные учебные семестры.";
                apiParams = academicTimeOracle.getCurrentTermAndWeek(); // Fallback на текущие параметры
                if (apiParams == null) { // Если даже текущая дата вне семестра - это плохо
                    log.error("Критическая ошибка: даже текущая дата {} вне известных семестров!", today);
                    // Создаем "пустой" или ошибочный PlatonusScheduleRequest
                    apiParams = new PlatonusScheduleRequest(0,0); // Сигнал об ошибке
                    summary += " Не удалось определить текущий семестр/неделю.";
                    finalQueryType = ResolvedQueryType.ERROR_GENERIC;
                }
                log.warn("DRS: {}", summary);
            } else {
                finalQueryType = ResolvedQueryType.SPECIFIC_DAY_REQUESTED;
                dayFilter = Optional.of(resolvedDate.getDayOfWeek());
                summary = String.format("Запрос на %s (%s), семестр: %d, неделя: %d",
                        logPhrase.equals(userInput) ? "указанную дату" : logPhrase,
                        resolvedDate.format(USER_FRIENDLY_DATE_FORMATTER),
                        apiParams.term(),
                        apiParams.week());
            }
        } else if (explicitWeekNumber.isPresent()) {
            int weekNum = explicitWeekNumber.get();
            if (weekNum < 1 || weekNum > 15) {
                finalQueryType = ResolvedQueryType.ERROR_INVALID_WEEK_NUMBER;
                summary = "Ошибка: указан неверный номер недели (" + weekNum + "). Допустимы недели 1-15.";
                apiParams = academicTimeOracle.getCurrentTermAndWeek();
                if (apiParams == null) {
                    apiParams = new PlatonusScheduleRequest(0,0);
                    summary += " Не удалось определить текущий семестр.";
                    finalQueryType = ResolvedQueryType.ERROR_GENERIC;
                }
                log.warn("DRS: {}", summary);
            } else {
                PlatonusScheduleRequest currentContext = academicTimeOracle.getCurrentTermAndWeek();
                if (currentContext == null) {
                    log.error("Не удалось определить текущий семестр для явной недели {}", weekNum);
                    apiParams = new PlatonusScheduleRequest(0, weekNum); // term неизвестен
                    summary = String.format("%d-я неделя (указана пользователем), семестр НЕ ОПРЕДЕЛЕН", weekNum);
                    finalQueryType = ResolvedQueryType.ERROR_GENERIC;
                } else {
                    apiParams = new PlatonusScheduleRequest(currentContext.term(), weekNum);
                    finalQueryType = ResolvedQueryType.SPECIFIC_WEEK_REQUESTED_BY_USER;
                    summary = String.format("%d-я неделя (указана пользователем), семестр: %d",
                            weekNum, apiParams.term());
                }
            }
        } else {
            apiParams = academicTimeOracle.getCurrentTermAndWeek();
            if (apiParams == null) {
                log.error("Критическая ошибка: не удалось определить текущий семестр/неделю для даты {}", today);
                finalQueryType = ResolvedQueryType.ERROR_GENERIC;
                summary = "Ошибка: не удалось определить текущий учебный период.";
                apiParams = new PlatonusScheduleRequest(0,0); // Сигнал об ошибке
            } else {
                finalQueryType = ResolvedQueryType.CURRENT_WEEK_DEFAULT;
                specificDate = Optional.of(today);
                summary = String.format("Текущая неделя (%s), семестр: %d, неделя: %d",
                        today.format(USER_FRIENDLY_DATE_FORMATTER),
                        apiParams.term(),
                        apiParams.week());
            }
        }

        log.info("DRS Result: Type={}, UserDate={}, DayFilter={}, API_Params={}, Summary='{}'",
                finalQueryType,
                specificDate.map(d -> d.format(DateTimeFormatter.ISO_DATE)).orElse("N/A"),
                dayFilter.map(Enum::name).orElse("N/A"),
                apiParams,
                summary);

        return new DateResolutionDetails(specificDate, dayFilter, apiParams, finalQueryType, summary);
    }
}
