package bbt.tao.orchestra.tools.enu;


import bbt.tao.orchestra.dto.DateResolutionDetails;
import bbt.tao.orchestra.dto.ResolvedQueryType;
import bbt.tao.orchestra.dto.ScheduleFormatData;
import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusApiResponse;
import bbt.tao.orchestra.service.DateResolutionService;
import bbt.tao.orchestra.service.client.PlatonusPortalApiClient;
import bbt.tao.orchestra.tools.formatter.ToolResponseFormatter;
import bbt.tao.orchestra.tools.formatter.fabric.ResponseFormatterRegistry;
import bbt.tao.orchestra.tools.meta.AgentToolMeta;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Component
@Slf4j
public class ScheduleTool {
    public record ScheduleRequest(String userInput) {}

    private final DateResolutionService dateResolutionService;
    private final PlatonusPortalApiClient platonusApiClient;
    private final ToolResponseFormatter<ScheduleFormatData> scheduleFormatter;

    public ScheduleTool(DateResolutionService dateResolutionService,
                                PlatonusPortalApiClient platonusApiClient,
                                ResponseFormatterRegistry formatterRegistry) {
        this.dateResolutionService = dateResolutionService;
        this.platonusApiClient = platonusApiClient;
        this.scheduleFormatter = formatterRegistry.getFormatter(ScheduleFormatData.class);
    }

    @AgentToolMeta(
            description = """
                    Возвращает расписание студента ENU из Platonus по неделе и семестру: предметы, аудитории, преподавателей и формат занятий.
                    Ожидает пользовательский запрос на русском, извлекает неделю/семестр и формирует готовый ответ по дням.
                    """,
            examples = {
                    "Покажи расписание на текущую неделю",
                    "Найди расписание занятий на третью неделю осеннего семестра"
            }
    )
    @Tool(name = "getPlatonusStudentSchedule", description = """
            Используй этот инструмент, когда пользователь спрашивает о расписании, занятиях, парах, лекциях, семинарах, уроках.
            """, returnDirect = true)
    public String getSchedule(@ToolParam(description = "оригинальный текст пользователя!") String userInput) throws ExecutionException, InterruptedException {
        ScheduleRequest request = new ScheduleRequest(userInput);
        log.info("Инструмент '{}' вызван с userInput: '{}'", "getPlatonusStudentSchedule", request.userInput());

        DateResolutionDetails resolutionDetails;
        try {
            resolutionDetails = dateResolutionService.resolve(request.userInput());
        } catch (Exception e) {
            log.error("Ошибка при вызове dateResolutionService.resolve для userInput: '{}'", userInput, e);
            return "Ошибка при определении параметров запроса: " + e.getMessage();
        }
        if (resolutionDetails.queryType() == ResolvedQueryType.ERROR_INVALID_WEEK_NUMBER ||
                resolutionDetails.queryType() == ResolvedQueryType.ERROR_DATE_OUT_OF_SEMESTER ||
                resolutionDetails.queryType() == ResolvedQueryType.ERROR_GENERIC ||
                resolutionDetails.apiRequestParams() == null ||
                resolutionDetails.apiRequestParams().term() == null || resolutionDetails.apiRequestParams().term() == 0 ||
                resolutionDetails.apiRequestParams().week() == null || resolutionDetails.apiRequestParams().week() == 0) {
            log.warn("Ошибка разрешения даты/недели для инструмента '{}': {}", "getPlatonusStudentSchedule", resolutionDetails.userFriendlySummary());
            return resolutionDetails.userFriendlySummary();
        }

        log.info("Для инструмента '{}' определены параметры для API Платонуса: {}. Пользовательский запрос: '{}'",
                "getPlatonusStudentSchedule",
                resolutionDetails.apiRequestParams(), // Логируем весь объект
                resolutionDetails.userFriendlySummary()
        );

        PlatonusApiResponse apiResponse = platonusApiClient.fetchTimetable(
                        resolutionDetails.apiRequestParams().term(),
                        resolutionDetails.apiRequestParams().week()
        );

        if (apiResponse == null) {
            log.error("API Платонуса вернул пустой ответ (null) для инструмента '{}'", "getPlatonusStudentSchedule");
            return "Не удалось получить данные от сервера расписания (ответ пуст). Пожалуйста, попробуйте позже.";
        }

        ScheduleFormatData formatData = new ScheduleFormatData(apiResponse, resolutionDetails);

        String formattedSchedule = scheduleFormatter.format(formatData);
        log.debug("Сформированное расписание инструментом '{}' (первые 150 символов): {}", "getPlatonusStudentSchedule", formattedSchedule.substring(0, Math.min(formattedSchedule.length(), 150)).replace(" ", " "));
        return formattedSchedule;

    }
}



