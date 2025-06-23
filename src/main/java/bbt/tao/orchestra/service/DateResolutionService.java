package bbt.tao.orchestra.service;


import bbt.tao.orchestra.dto.DateResolutionDetails;
import bbt.tao.orchestra.dto.RegexParseOutput;
import bbt.tao.orchestra.dto.ResolvedQueryType;
import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusScheduleRequest;
import bbt.tao.orchestra.util.AcademicTime;
import bbt.tao.orchestra.util.LLMDateParser;
import bbt.tao.orchestra.util.RegexProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
public class DateResolutionService {
    private final RegexProcessor regexProcessor;
    private final LLMDateParser llmDateParser; // Используем ваше имя класса
    private final AcademicTime academicTime;   // Используем ваше имя класса
    private final Clock clock;

    private static final DateTimeFormatter USER_FRIENDLY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMMM yyyy", new Locale("ru"));

    public DateResolutionService(RegexProcessor regexProcessor, LLMDateParser llmDateParser, AcademicTime academicTime, Clock clock) {
        this.regexProcessor = regexProcessor;
        this.llmDateParser = llmDateParser;
        this.academicTime = academicTime;
        this.clock = clock;
    }

    public DateResolutionDetails resolve(String userInput) {
        LocalDate today = LocalDate.now(clock);
        Optional<LocalDate> specificDate = Optional.empty();
        Optional<Integer> explicitWeekNumberByRegex = Optional.empty();
        String logPhrase = userInput;

        RegexParseOutput regexOutput = regexProcessor.process(userInput, today);
        if (regexOutput.weekNumber().isPresent()) {
            explicitWeekNumberByRegex = regexOutput.weekNumber();
            logPhrase = regexOutput.matchedPhraseForLog();
            log.info("DRS: Regex извлек номер недели: {}, фраза: '{}'", explicitWeekNumberByRegex.get(), logPhrase);
        } else if (regexOutput.specificDate().isPresent()) {
            specificDate = regexOutput.specificDate();
            logPhrase = regexOutput.matchedPhraseForLog();
            log.info("DRS: Regex извлек простую относительную дату: {}, фраза: '{}'", specificDate.get(), logPhrase);
        }

        if (specificDate.isEmpty() && explicitWeekNumberByRegex.isEmpty()) {
            log.info("DRS: Regex не извлек неделю или простую дату. Запрос к LLM для: '{}'", userInput);
            specificDate = llmDateParser.extractDate(userInput, today);
            if (specificDate.isPresent()) {
                logPhrase = "дата извлечена LLM";
                log.info("DRS: LLM извлек дату: {}", specificDate.get());
            } else {
                log.info("DRS: LLM также не извлек конкретную дату из: '{}'", userInput);
            }
        }

        PlatonusScheduleRequest apiParams;
        ResolvedQueryType finalQueryType;
        String summary;
        Optional<DayOfWeek> dayFilter = Optional.empty();

        if (specificDate.isPresent()) {
            LocalDate resolvedDate = specificDate.get();
            apiParams = academicTime.getTermAndWeek(resolvedDate);
            if (apiParams == null) {
                finalQueryType = ResolvedQueryType.ERROR_DATE_OUT_OF_SEMESTER;
                summary = "По нашим данным, дата " + resolvedDate.format(USER_FRIENDLY_DATE_FORMATTER) +
                        " не попадает в учебный семестр. API Платонуса использует семестр по умолчанию (1-й).";
                log.warn("DRS: {}", summary);
                int yearForHypotheticalSem1 = resolvedDate.getMonthValue() >= academicTime.getSemester1StartMonthDefault() ? resolvedDate.getYear() : resolvedDate.getYear() -1;
                LocalDate hypotheticalSem1Start = LocalDate.of(yearForHypotheticalSem1, academicTime.getSemester1StartMonthDefault(), academicTime.getSemester1StartDayDefault());

                long daysBetween = ChronoUnit.DAYS.between(hypotheticalSem1Start, resolvedDate);
                int calculatedWeek = (int) (daysBetween / 7) + 1;
                calculatedWeek = Math.max(1, Math.min(15, calculatedWeek));
                apiParams = new PlatonusScheduleRequest(1, calculatedWeek);
                log.warn("DRS: Дата вне семестра по нашей логике. Отправляем в API term=1, week={}", calculatedWeek);
            } else {
                finalQueryType = ResolvedQueryType.SPECIFIC_DAY_REQUESTED;
                dayFilter = Optional.of(resolvedDate.getDayOfWeek());
                summary = String.format("Запрос на %s (%s), семестр: %d, неделя: %d",
                        logPhrase,
                        resolvedDate.format(USER_FRIENDLY_DATE_FORMATTER),
                        apiParams.term(),
                        apiParams.week());
            }
        } else if (explicitWeekNumberByRegex.isPresent()) {
            int weekNum = explicitWeekNumberByRegex.get();
            if (weekNum < 1 || weekNum > 15) {
                finalQueryType = ResolvedQueryType.ERROR_INVALID_WEEK_NUMBER;
                summary = "Ошибка: указан неверный номер недели (" + weekNum + "). Допустимы недели 1-15.";
                log.warn("DRS: {}", summary);
                apiParams = new PlatonusScheduleRequest(1, weekNum);
            } else {
                PlatonusScheduleRequest contextParams = academicTime.getContextualTermAndWeek();
                if (contextParams == null || contextParams.term() == 0) {
                    log.warn("DRS: Не удалось определить контекстный семестр для недели {}. API Платонуса использует term=1.", weekNum);
                    apiParams = new PlatonusScheduleRequest(1, weekNum); // Дефолт API
                    summary = String.format("%d-я неделя, семестр: 1 (предполагаемый API)", weekNum);
                } else {
                    apiParams = new PlatonusScheduleRequest(contextParams.term(), weekNum);
                    summary = String.format("%d-я неделя, семестр: %d (контекстный)", weekNum, apiParams.term());
                }
                finalQueryType = ResolvedQueryType.SPECIFIC_WEEK_REQUESTED_BY_USER;
            }
        } else {
            apiParams = academicTime.getCurrentTermAndWeek();
            if (apiParams == null) {
                finalQueryType = ResolvedQueryType.ERROR_DATE_OUT_OF_SEMESTER;
                summary = "По нашим данным, текущая дата (" + today.format(USER_FRIENDLY_DATE_FORMATTER) +
                        ") не попадает в учебный семестр. API Платонуса использует семестр по умолчанию (1-й).";
                log.warn("DRS: {}", summary);
                apiParams = new PlatonusScheduleRequest(1, 1);
            } else {
                finalQueryType = ResolvedQueryType.CURRENT_WEEK_DEFAULT;
                specificDate = Optional.of(today); // Для userRequestedDate
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
