package bbt.tao.orchestra.tools.enu;

import bbt.tao.orchestra.dto.enu.platonus.FormattedGradesResponse;
import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusScheduleRequest;
import bbt.tao.orchestra.exception.UnauthorizedException;
import bbt.tao.orchestra.service.client.PlatonusPortalApiClient;
import bbt.tao.orchestra.tools.formatter.ToolResponseFormatter;
import bbt.tao.orchestra.tools.formatter.fabric.ResponseFormatterRegistry;
import bbt.tao.orchestra.util.AcademicTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.util.concurrent.ExecutionException;

@Component
@Slf4j
public class PlatonusStudentGradesTool {


    private final AcademicTime academicTime;
    private final PlatonusPortalApiClient platonusPortalApiClient;
    private final ToolResponseFormatter<FormattedGradesResponse> platonusGradesResponseFormatter;

    public PlatonusStudentGradesTool(AcademicTime academicTime,
                                     PlatonusPortalApiClient platonusPortalApiClient,
                                     ResponseFormatterRegistry responseFormatterRegistry) {
        this.academicTime = academicTime;
        this.platonusPortalApiClient = platonusPortalApiClient;
        this.platonusGradesResponseFormatter = responseFormatterRegistry.getFormatter(FormattedGradesResponse.class);
    }

    @Tool(name = "getGrades", description = """
            узнать оценки, получить текущие оценки за семестр.
            """,
            returnDirect = true)
    public String getGrades() throws ExecutionException, InterruptedException {
        log.info("Инструмент '{}' вызван для получения текущих оценок.", "getGrades");
        PlatonusScheduleRequest request = academicTime.getCurrentTermAndWeek();
        int academicYear = academicTime.getRelevantAcademicYearStartForDate(LocalDate.now());
        String lang = "ru"; // TODO: Реализовать поддержку языков
        log.info("Получен запрос на получение оценок за семестр {} для академического года {}", request.term(), academicYear);

        if (request.term() == null || request.term() == 0) {
            String errorMsg = "Не удалось определить текущий учебный период для отображения оценок (сегодня: " + LocalDate.now() + ").";
            log.warn(errorMsg);
            return errorMsg;
        }

        return platonusPortalApiClient.fetchAndParseGrades(academicYear, request.term(), lang)
                .map(semesterPerformance -> {
                    if (semesterPerformance == null) {
                        log.warn("StudentGradesTool: gradesScraperService.fetchAndParseGrades вернул null для Год={}, Семестр={}", academicYear, request.term());
                        return "Ошибка: сервис оценок не вернул данные.";
                    }

                    if (!semesterPerformance.getParsingIssues().isEmpty()) {
                        String issues = String.join("; ", semesterPerformance.getParsingIssues());
                        log.warn("StudentGradesTool: Обнаружены проблемы при парсинге оценок: {}", issues);
                        if (semesterPerformance.getSubjects().isEmpty()) {
                            return "Не удалось полностью обработать страницу с оценками. Проблемы: " + issues;
                        }
                    }

                    if (semesterPerformance.getSubjects().isEmpty()) {
                        return "Оценки за текущий период (Год: " + academicYear + ", Семестр: " + request.term() + ") не найдены (данные отсутствуют).";
                    }

                    String recommendations = "";
                    String scholarshipInfo = "";

                    FormattedGradesResponse dataToFormat = new FormattedGradesResponse(semesterPerformance, recommendations, scholarshipInfo);
                    String formattedOutput = platonusGradesResponseFormatter.format(dataToFormat);
                    log.info("StudentGradesTool: Оценки успешно отформатированы.");
                    System.out.println("StudentGradesTool: Форматированный вывод оценок:\n" + formattedOutput);
                    return formattedOutput;
                })
                .onErrorResume(e -> {
                    log.error("StudentGradesTool: Ошибка в реактивной цепочке получения оценок: {}", e.getMessage(), e);
                    if (e instanceof UnauthorizedException) {
                        return Mono.just("Ошибка авторизации в системе Платонус. Пожалуйста, попробуйте позже или обратитесь к администратору.");
                    }
                    return Mono.just("К сожалению, произошла внутренняя ошибка при получении ваших оценок: " + e.getClass().getSimpleName());
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("StudentGradesTool: gradesScraperService.fetchAndParseGrades вернул пустой Mono для Год={}, Семестр={}", academicYear, request.term());
                    return Mono.just("Не удалось получить данные об оценках (сервис не вернул результат).");
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture()
                .get();
    }
}
