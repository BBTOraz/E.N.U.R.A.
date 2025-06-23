package bbt.tao.orchestra.service.client;

import bbt.tao.orchestra.dto.enu.platonus.grades.StudentSemesterPerformance;
import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusApiResponse;
import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusScheduleRequest;
import bbt.tao.orchestra.dto.enu.platonus.SemesterGrades;
import bbt.tao.orchestra.exception.UnauthorizedException;
import bbt.tao.orchestra.manager.init.PlatonusApiAuthenticator;
import bbt.tao.orchestra.util.PlatonusGradesParser;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
public class PlatonusPortalApiClient {
    private final WebClient platonusWebClient;

    private static final String PERSON_ID_API_PATH = "/rest/api/person/personID";
    private static final String BASE_SCHEDULE_API_PATH = "/rest/schedule/userSchedule/student/calculate/";
    private final PlatonusApiAuthenticator platonusApiAuthenticator;
    private final PlatonusGradesParser platonusGradesParser;

    private record PersonIdResponse(
            @JsonProperty("cryptPersonID") String cryptPersonID,
            @JsonProperty("personID") Integer personID
    ) {}

    public PlatonusPortalApiClient(@Qualifier("platonusWebClient") WebClient platonusWebClient, PlatonusApiAuthenticator platonusApiAuthenticator, PlatonusGradesParser platonusGradesParser) {
        this.platonusWebClient = platonusWebClient;
        this.platonusApiAuthenticator = platonusApiAuthenticator;
        this.platonusGradesParser = platonusGradesParser;
    }

    private Mono<Integer> fetchCurrentUserId() {
        log.info("Запрос ID пользователя с сервера Платонуса: {}", PERSON_ID_API_PATH);
        return platonusWebClient.get()
                .uri(PERSON_ID_API_PATH)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(bodyAsString -> {
                    log.debug("Ответ от {}: {}", PERSON_ID_API_PATH, bodyAsString);
                    if (bodyAsString == null || bodyAsString.trim().isEmpty() || bodyAsString.trim().equals("{}")) {
                        log.warn("API получения ID пользователя ({}) вернул пустой или невалидный ответ: '{}'. Предполагаем проблему с авторизацией.", PERSON_ID_API_PATH, bodyAsString);
                        platonusApiAuthenticator.invalidateSession();
                        return Mono.error(new UnauthorizedException("Пустой или невалидный ответ от API personID, возможно, сессия истекла."));
                    }
                    try {
                        ObjectMapper tempMapper = new ObjectMapper();
                        PersonIdResponse response = tempMapper.readValue(bodyAsString, PersonIdResponse.class);

                        if (response != null && response.personID() != null) {
                            log.info("ID пользователя успешно получен: {}", response.personID());
                            return Mono.just(response.personID());
                        } else {
                            log.error("API получения ID пользователя ({}) вернул ответ без personID: {}", PERSON_ID_API_PATH, bodyAsString);
                            platonusApiAuthenticator.invalidateSession();
                            return Mono.error(new UnauthorizedException("Ответ от API personID не содержит personID, возможно, сессия истекла."));
                        }
                    } catch (JsonProcessingException e) {
                        log.error("Ошибка парсинга JSON ответа от {}: {}", PERSON_ID_API_PATH, bodyAsString, e);
                        return Mono.error(new IllegalStateException("Не удалось распарсить ответ от API personID.", e));
                    }
                })
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                        .filter(ex -> ex instanceof UnauthorizedException)
                        .doBeforeRetry(retrySignal -> log.warn("Повторная попытка получения ID пользователя после UnauthorizedException: {}", retrySignal.failure().getMessage()))
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                            log.error("Не удалось получить ID пользователя после нескольких попыток из-за проблем с авторизацией.", retrySignal.failure());
                            return retrySignal.failure();
                        })
                )
                .doOnError(error -> {
                    if (!(error instanceof UnauthorizedException)) {
                        log.error("Финальная ошибка (не Unauthorized) при получении ID пользователя: {}", error.getMessage(), error);
                    }
                });
    }


    public Mono<PlatonusApiResponse> fetchTimetable(Integer term, Integer week, String languageCode) {
        if (term == null || week == null || languageCode == null || languageCode.isBlank()) {
            log.error("Некорректные параметры для запроса расписания: term={}, week={}, languageCode={}", term, week, languageCode);
            return Mono.error(new IllegalArgumentException("Term, week, и languageCode не могут быть null или пустыми."));
        }

        return fetchCurrentUserId().flatMap(userId -> {
            if (userId == null) {
                log.error("Не удалось получить ID пользователя, запрос расписания невозможен.");
                return Mono.error(new IllegalStateException("Не удалось получить ID пользователя для запроса расписания."));
            }

            String schedulePath = BASE_SCHEDULE_API_PATH + userId + "/" + languageCode.toLowerCase(Locale.ROOT);
            PlatonusScheduleRequest requestBody = new PlatonusScheduleRequest(term, week);

            log.info("Запрос расписания из Платонуса: Path='{}', Body={}", schedulePath, requestBody);

            return platonusWebClient.post()
                    .uri(schedulePath)
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(PlatonusApiResponse.class)
                    .retryWhen(Retry.backoff(1, Duration.ofMillis(700))
                            .filter(ex -> ex instanceof UnauthorizedException)
                            .doBeforeRetry(sig -> log.warn("Повторный запрос расписания после UnauthorizedException. Сессия могла быть обновлена."))
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                                log.error("Не удалось получить расписание после ошибки авторизации (попытки исчерпаны).", retrySignal.failure());
                                return retrySignal.failure();
                            })
                    )
                    .doOnError(err -> {
                        if (!(err instanceof UnauthorizedException)) {
                            log.error("Ошибка при вызове API расписания Платонуса: Path='{}', Body={}. Ошибка: {}",
                                    schedulePath, requestBody, err.getMessage(), err);
                        }
                    })
                    .onErrorResume(ex -> !(ex instanceof UnauthorizedException), ex -> {
                        log.error("Непредвиденная ошибка (не Unauthorized) при запросе расписания из Платонуса.", ex);
                        return Mono.empty();
                    });
        }).onErrorResume(ex -> {
            log.warn("Не удалось выполнить запрос расписания: проблема с авторизацией при получении ID пользователя.", ex);
            return Mono.error(ex);
        }).onErrorResume(ex -> !(ex instanceof UnauthorizedException), ex -> {
            log.error("Не удалось выполнить запрос расписания из-за другой ошибки получения ID пользователя: {}", ex.getMessage(), ex);
            return Mono.empty();
        });
    }

    public PlatonusApiResponse fetchTimetable(Integer term, Integer week) throws ExecutionException, InterruptedException {
        return fetchTimetable(term, week, "ru")
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture().get();
    }


    public Mono<StudentSemesterPerformance> fetchAndParseGrades(int academicYear, int term, String lang) {
        return fetchCurrentUserId().flatMap(studentId -> {
            String url = String.format("/current_progress_gradebook_student?studentID=%d&year=%d&term=%d&lang=%s&print=true",
                    studentId, academicYear, term, lang);
            log.info("Запрос HTML страницы оценок для studentId {}: {}", studentId, url);

            return platonusWebClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnSuccess(content -> log.debug("Получен HTML контент для studentId {}: ", studentId))
                    .map(htmlContent -> platonusGradesParser.parse(htmlContent, String.valueOf(studentId), String.valueOf(academicYear), String.valueOf(term)))
                    .doOnError(e -> log.error("Ошибка при получении или парсинге страницы оценок для studentId {}: {}", studentId, e.getMessage(), e))
                    .onErrorResume(e -> { // Ловим ошибки получения HTML или парсинга
                        log.error("Возвращаем пустые оценки (с сообщением об ошибке) из-за ошибки для studentId {}: {}", studentId, e.getMessage());
                        StudentSemesterPerformance errorPerformance = new StudentSemesterPerformance(
                                String.valueOf(academicYear), String.valueOf(term), String.valueOf(studentId),
                                Collections.emptyList(), List.of("Ошибка сервера при получении или парсинге оценок: " + e.getMessage()));
                        return Mono.just(errorPerformance);
                    });
        }).onErrorResume(Exception.class, ex -> {
            String errorMsg = "Не удалось получить studentId из-за ошибки (" + ex.getClass().getSimpleName() + "), скрапинг оценок невозможен: " + ex.getMessage();
            log.error(errorMsg, ex);
            StudentSemesterPerformance errorPerformance = new StudentSemesterPerformance(
                    String.valueOf(academicYear), String.valueOf(term), "UNKNOWN_ID_ERROR", Collections.emptyList(), List.of(errorMsg));
            return Mono.just(errorPerformance);
        });
    }
}