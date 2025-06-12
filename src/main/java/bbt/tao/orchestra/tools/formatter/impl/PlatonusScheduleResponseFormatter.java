package bbt.tao.orchestra.tools.formatter.impl;

import bbt.tao.orchestra.dto.DateResolutionDetails;
import bbt.tao.orchestra.dto.ResolvedQueryType;
import bbt.tao.orchestra.dto.ScheduleFormatData;
import bbt.tao.orchestra.dto.enu.platonus.DayEntry;
import bbt.tao.orchestra.dto.enu.platonus.LessonDetail;
import bbt.tao.orchestra.dto.enu.platonus.LessonHour;
import bbt.tao.orchestra.dto.enu.platonus.PlatonusApiResponse;
import bbt.tao.orchestra.tools.formatter.ToolResponseFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Component
@Slf4j
public class PlatonusScheduleResponseFormatter implements ToolResponseFormatter<ScheduleFormatData> {

    private final Locale RUSSIAN_LOCALE = new Locale("ru");

    private String getLocalizedDayName(DayOfWeek dayOfWeek, Locale locale) {
        if (dayOfWeek == null) return "Неизвестный день";
        return dayOfWeek.getDisplayName(TextStyle.FULL_STANDALONE, locale);
    }

    private String getLocalizedDayNameFromApiKey(String dayKeyStr, Locale locale) {
        try {
            int dayKey = Integer.parseInt(dayKeyStr);
            if (dayKey >= 1 && dayKey <= 7) {
                return DayOfWeek.of(dayKey).getDisplayName(TextStyle.FULL_STANDALONE, locale);
            }
        } catch (NumberFormatException e) {
            log.warn("Неверный ключ дня из API для локализации: {}", dayKeyStr);
        }
        return "День " + dayKeyStr;
    }

    @Override
    public String format(ScheduleFormatData formatData) {
        PlatonusApiResponse apiResponse = formatData.apiResponse();
        DateResolutionDetails resolutionDetails = formatData.resolutionResult();

        if (resolutionDetails.queryType() == ResolvedQueryType.ERROR_INVALID_WEEK_NUMBER ||
            resolutionDetails.queryType() == ResolvedQueryType.ERROR_DATE_OUT_OF_SEMESTER ||
            resolutionDetails.queryType() == ResolvedQueryType.ERROR_GENERIC ||
            (resolutionDetails.apiRequestParams() != null &&
             (resolutionDetails.apiRequestParams().term() == null || resolutionDetails.apiRequestParams().term() == 0 ||
              resolutionDetails.apiRequestParams().week() == null || resolutionDetails.apiRequestParams().week() == 0))) {
            return resolutionDetails.userFriendlySummary();
        }

        if (apiResponse == null || apiResponse.timetable() == null || apiResponse.lessonHours() == null) {
            log.warn("Форматирование расписания: получен неполный или нулевой ответ от API Platonus.");
            return "К сожалению, не удалось получить полные данные о расписании от сервера.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🗓️ Расписание для: **").append(resolutionDetails.userFriendlySummary()).append("**\n");

        Map<Integer, LessonHour> lessonHoursMap = apiResponse.lessonHours().stream()
                .collect(Collectors.toMap(LessonHour::number, lh -> lh, (lh1, lh2) -> lh1)); // В случае дубликатов берем первый


        Optional<DayOfWeek> specificDayToDisplay = resolutionDetails.dayOfWeekToFilter();

        if (specificDayToDisplay.isPresent()) {

            DayOfWeek day = specificDayToDisplay.get();
            String dayApiKey = String.valueOf(day.getValue());
            String localizedDayName = getLocalizedDayName(day, RUSSIAN_LOCALE);

            boolean hasLessons = formatSingleDayContent(sb, apiResponse.timetable().days().get(dayApiKey), lessonHoursMap, localizedDayName);
            if (!hasLessons) {
                sb.append("\n   На ").append(localizedDayName.toLowerCase(RUSSIAN_LOCALE)).append(" занятий не найдено.\n");
            }
        } else {

            Map<String, DayEntry> daysFromApi = apiResponse.timetable().days();
            if (daysFromApi == null || daysFromApi.isEmpty()) {
                sb.append("\nНа этой неделе занятий нет.\n");
            } else {
                boolean weekHasAnyLessons = false;
                List<Map.Entry<String, DayEntry>> sortedDays = daysFromApi.entrySet().stream()
                        .sorted(Comparator.comparingInt(entry -> Integer.parseInt(entry.getKey())))
                        .toList();

                for (Map.Entry<String, DayEntry> dayApiEntry : sortedDays) {
                    String dayApiKey = dayApiEntry.getKey();
                    String localizedDayName = getLocalizedDayNameFromApiKey(dayApiKey, RUSSIAN_LOCALE);
                    if (formatSingleDayContent(sb, dayApiEntry.getValue(), lessonHoursMap, localizedDayName)) {
                        weekHasAnyLessons = true;
                    }
                }
                if (!weekHasAnyLessons) {
                    sb.append("\nНа этой неделе занятий нет.\n");
                }
            }
        }

        String result = sb.toString().trim();
        while (result.endsWith("\n\n")) {
            result = result.substring(0, result.length() -1);
        }
        return result;
    }

    private boolean formatSingleDayContent(StringBuilder sb,
                                           DayEntry dayDataFromApi,
                                           Map<Integer, LessonHour> lessonHoursMap,
                                           String localizedDayName) {
        if (dayDataFromApi == null || dayDataFromApi.lessons() == null || dayDataFromApi.lessons().isEmpty()) {
            return false; // Занятий нет или нет данных по дню
        }

        // Собираем все LessonDetail за день и сортируем их по номеру пары (который является LessonHour.number)
        List<LessonDetail> lessonsForDay = dayDataFromApi.lessons().entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().lessons() != null && !entry.getValue().lessons().isEmpty())
                .flatMap(entry -> entry.getValue().lessons().stream())
                // Убедимся, что у LessonDetail есть номер, соответствующий ключу в lessonHoursMap
                .filter(ld -> lessonHoursMap.containsKey(ld.number()))
                .sorted(Comparator.comparingInt(LessonDetail::number))
                .toList();

        if (lessonsForDay.isEmpty()) {
            return false; // Нет валидных занятий для отображения
        }

        sb.append("\n🎓 **").append(localizedDayName.toUpperCase(RUSSIAN_LOCALE)).append("**\n");

        for (LessonDetail lesson : lessonsForDay) {
            LessonHour lessonTime = lessonHoursMap.get(lesson.number()); // lesson.number должен быть ключом из lessonHours
            if (lessonTime == null) { // Дополнительная проверка, хотя выше отфильтровали
                log.warn("Не найдено время для пары номер {} (предмет: {})", lesson.number(), lesson.subjectName());
                continue;
            }

            sb.append(String.format("  %s-%s (%s пара)\n",
                    lessonTime.start().substring(0, 5), // "08:00"
                    lessonTime.finish().substring(0, 5),// "08:50"
                    lessonTime.displayNumber()          // 1, 2, 3...
            ));
            sb.append("    📚 **").append(lesson.subjectName()).append("**");
            if (lesson.groupTypeShortName() != null && !lesson.groupTypeShortName().isBlank()) {
                sb.append(" (").append(lesson.groupTypeShortName()).append(")");
            }
            sb.append("\n");

            if (lesson.tutorName() != null && !lesson.tutorName().trim().isEmpty()) {
                sb.append("    🧑‍🏫 Преподаватель: ").append(lesson.tutorName().trim()).append("\n");
            }
            if (lesson.auditory() != null && !lesson.auditory().isBlank()) {
                sb.append("    🚪 Аудитория: ").append(lesson.auditory());
                if (lesson.building() != null && !lesson.building().isBlank()) {
                    sb.append(" (").append(lesson.building()).append(")");
                }
                sb.append("\n");
            } else if (lesson.onlineClass()) {
                sb.append("    💻 Формат: Онлайн\n");
            }
            sb.append("\n"); // Пустая строка между парами для читаемости
        }
        return true; // Были занятия
    }
}
