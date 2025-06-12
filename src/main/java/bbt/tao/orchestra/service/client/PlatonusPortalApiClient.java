package bbt.tao.orchestra.service.client;

import bbt.tao.orchestra.dto.enu.platonus.PlatonusApiResponse;
import bbt.tao.orchestra.dto.enu.platonus.PlatonusScheduleRequest;
import bbt.tao.orchestra.exception.UnauthorizedException;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ForkJoinPool;

@Service
@Slf4j
public class PlatonusPortalApiClient {
    private final WebClient platonusWebClient; // Этот WebClient уже настроен с authenticationFilter

    private static final String PERSON_ID_API_PATH = "/rest/api/person/personID";
    private static final String BASE_SCHEDULE_API_PATH = "/rest/schedule/userSchedule/student/calculate/";

    private record PersonIdResponse(
            @JsonProperty("cryptPersonID") String cryptPersonID,
            @JsonProperty("personID") Integer personID
    ) {}

    public PlatonusPortalApiClient(@Qualifier("platonusWebClient") WebClient platonusWebClient) {
        this.platonusWebClient = platonusWebClient;
    }

    /**
     * Получает ID пользователя с сервера.
     * Этот метод будет вызван КАЖДЫЙ РАЗ, когда нужен ID, но запрос пройдет через
     * аутентификационный фильтр, который обеспечит валидность сессии.
     * @return Mono с ID пользователя.
     */
    private Mono<Integer> fetchCurrentUserId() {
        log.info("Запрос ID пользователя с сервера Платонуса: {}", PERSON_ID_API_PATH);
        return platonusWebClient.get()
                .uri(PERSON_ID_API_PATH)
                .retrieve()
                .bodyToMono(PersonIdResponse.class)
                .map(response -> {
                    if (response != null && response.personID() != null) {
                        log.info("ID пользователя успешно получен: {}", response.personID());
                        return response.personID();
                    } else {
                        log.error("API получения ID пользователя ({}) вернул некорректный ответ: {}", PERSON_ID_API_PATH, response);
                        throw new IllegalStateException("Не удалось получить валидный ID пользователя от API Платонуса.");
                    }
                })
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500))
                        .filter(ex -> !(ex instanceof IllegalStateException || ex instanceof UnauthorizedException))
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) ->
                                new RuntimeException("Не удалось получить ID пользователя после нескольких попыток.", retrySignal.failure())
                        )
                )
                .doOnError(error -> log.error("Ошибка при получении ID пользователя с сервера: {}", error.getMessage(), error));
    }


    public Mono<PlatonusApiResponse> fetchTimetable(Integer term, Integer week, String languageCode) {
        if (term == null || week == null || languageCode == null || languageCode.isBlank()) {
            log.error("Некорректные параметры для запроса расписания: term={}, week={}, languageCode={}", term, week, languageCode);
            return Mono.error(new IllegalArgumentException("Term, week, и languageCode не могут быть null или пустыми."));
        }

        return fetchCurrentUserId().flatMap(userId -> {
            if (userId == null) { // Дополнительная проверка, хотя fetchCurrentUserId должен кинуть исключение
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
                            .filter(ex -> ex instanceof UnauthorizedException) // Повтор только при Unauthorized
                            .doBeforeRetry(sig -> log.warn("Повторный запрос расписания после UnauthorizedException. Сессия могла быть обновлена."))
                            .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                                log.error("Не удалось получить расписание после ошибки авторизации (попытки исчерпаны).", retrySignal.failure());
                                // Пробрасываем исходное исключение UnauthorizedException, если это оно
                                if (retrySignal.failure() instanceof UnauthorizedException) {
                                    return retrySignal.failure();
                                }
                                return new RuntimeException("Не удалось получить расписание после ошибки авторизации.", retrySignal.failure());
                            })
                    )
                    .doOnError(err -> {
                        if (!(err instanceof UnauthorizedException)) { // Логируем, только если это не ошибка авторизации, т.к. она уже обработана выше
                            log.error("Ошибка при вызове API расписания Платонуса: Path='{}', Body={}. Ошибка: {}",
                                    schedulePath, requestBody, err.getMessage(), err);
                        }
                    })
                    // UnauthorizedException будет проброшена из retryWhen, если он не смог ее решить.
                    // Для других ошибок можно вернуть пустой Mono.
                    .onErrorResume(ex -> !(ex instanceof UnauthorizedException), ex -> {
                        log.error("Непредвиденная ошибка (не Unauthorized) при запросе расписания из Платонуса.", ex);
                        return Mono.empty();
                    });
        }).onErrorResume(ex -> { // Обработка ошибок от fetchCurrentUserId()
            log.error("Не удалось выполнить запрос расписания из-за ошибки получения ID пользователя: {}", ex.getMessage(), ex);
            // Если ошибка была UnauthorizedException из fetchCurrentUserId, то ее и пробрасываем
            if (ex instanceof UnauthorizedException) {
                return Mono.error(ex);
            }
            // Для других ошибок получения ID пользователя, которые не Unauthorized, возвращаем пустой результат
            // чтобы PlatonusScheduleTool мог сообщить об общей проблеме
            return Mono.empty();
        });
    }

    /**
     * Перегруженный метод для обратной совместимости или если язык по умолчанию известен.
     * Использует "kz" как язык по умолчанию.
     */
    public Mono<PlatonusApiResponse> fetchTimetable(Integer term, Integer week) {
        return fetchTimetable(term, week, "ru"); // Язык по умолчанию
    }
}
