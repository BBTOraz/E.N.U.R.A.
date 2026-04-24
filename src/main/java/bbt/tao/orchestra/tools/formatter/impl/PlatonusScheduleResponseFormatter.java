package bbt.tao.orchestra.tools.formatter.impl;

import bbt.tao.orchestra.dto.DateResolutionDetails;
import bbt.tao.orchestra.dto.ResolvedQueryType;
import bbt.tao.orchestra.dto.ScheduleFormatData;
import bbt.tao.orchestra.dto.enu.platonus.schedule.DayEntry;
import bbt.tao.orchestra.dto.enu.platonus.schedule.LessonDetail;
import bbt.tao.orchestra.dto.enu.platonus.schedule.LessonHour;
import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusApiResponse;
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

    private static final String NEW_LINE = "\n";
    private static final String DIVIDER = "---";
    private final Locale RUSSIAN_LOCALE = new Locale("ru");

    @Override
    public String format(ScheduleFormatData formatData) {
        PlatonusApiResponse apiResponse = formatData.apiResponse();
        DateResolutionDetails resolutionDetails = formatData.resolutionResult();
        log.info("Форматирование расписания: получены данные для периода '{}', тип запроса: {}, дата запроса: {}, день недели для фильтрации: {}, параметры API: {}",
                resolutionDetails.userFriendlySummary(), resolutionDetails.queryType(), resolutionDetails.userRequestedDate(), resolutionDetails.dayOfWeekToFilter(), resolutionDetails.apiRequestParams());
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
            return "❌ К сожалению, не удалось получить полные данные о расписании от сервера.";
        }

        StringBuilder sb = new StringBuilder();

        // Главный заголовок
        sb.append("# 📅 **РАСПИСАНИЕ ЗАНЯТИЙ**").append(NEW_LINE).append(NEW_LINE);
        sb.append("> **Период:** ").append(resolutionDetails.userFriendlySummary()).append(NEW_LINE).append(NEW_LINE);

        Map<Integer, LessonHour> lessonHoursMap = apiResponse.lessonHours().stream()
                .collect(Collectors.toMap(LessonHour::number, lh -> lh, (lh1, lh2) -> lh1));

        Optional<DayOfWeek> specificDayToDisplay = resolutionDetails.dayOfWeekToFilter();

        if (specificDayToDisplay.isPresent()) {
            DayOfWeek day = specificDayToDisplay.get();
            String dayApiKey = String.valueOf(day.getValue());
            String localizedDayName = getLocalizedDayName(day, RUSSIAN_LOCALE);

            boolean hasLessons = formatSingleDayContent(sb, apiResponse.timetable().days().get(dayApiKey), lessonHoursMap, localizedDayName);
            if (!hasLessons) {
                sb.append("## ℹ️ **Информация**").append(NEW_LINE).append(NEW_LINE);
                sb.append("На **").append(localizedDayName.toLowerCase(RUSSIAN_LOCALE)).append("** занятий не найдено.").append(NEW_LINE);
            }
        } else {
            Map<String, DayEntry> daysFromApi = apiResponse.timetable().days();
            if (daysFromApi == null || daysFromApi.isEmpty()) {
                sb.append("## ℹ️ **Информация**").append(NEW_LINE).append(NEW_LINE);
                sb.append("На этой неделе занятий нет.").append(NEW_LINE);
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
                    sb.append("## ℹ️ **Информация**").append(NEW_LINE).append(NEW_LINE);
                    sb.append("На этой неделе занятий нет.").append(NEW_LINE);
                }
            }
        }

        String result = sb.toString().trim();
        while (result.endsWith("\n\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean formatSingleDayContent(StringBuilder sb,
                                           DayEntry dayDataFromApi,
                                           Map<Integer, LessonHour> lessonHoursMap,
                                           String localizedDayName) {
        if (dayDataFromApi == null || dayDataFromApi.lessons() == null || dayDataFromApi.lessons().isEmpty()) {
            return false;
        }

        List<LessonDetail> lessonsForDay = dayDataFromApi.lessons().entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().lessons() != null && !entry.getValue().lessons().isEmpty())
                .flatMap(entry -> entry.getValue().lessons().stream())
                .filter(ld -> lessonHoursMap.containsKey(ld.number()))
                .sorted(Comparator.comparingInt(LessonDetail::number))
                .toList();

        if (lessonsForDay.isEmpty()) {
            return false;
        }

        // Заголовок дня
        sb.append("## **").append(localizedDayName.toUpperCase(RUSSIAN_LOCALE)).append("**").append(NEW_LINE).append(NEW_LINE);

        // Табличная сводка дня
        appendDaySummaryTable(sb, lessonsForDay, lessonHoursMap);

        // Детальная информация по каждой паре
        appendDetailedLessons(sb, lessonsForDay, lessonHoursMap);

        sb.append(DIVIDER).append(NEW_LINE).append(NEW_LINE);
        return true;
    }

    private void appendDaySummaryTable(StringBuilder sb, List<LessonDetail> lessonsForDay, Map<Integer, LessonHour> lessonHoursMap) {
        sb.append("### **Краткая сводка**").append(NEW_LINE).append(NEW_LINE);

        sb.append("| Время | Пара | Дисциплина | Тип | Аудитория |").append(NEW_LINE);
        sb.append("|:---:|:---:|:---|:---:|:---:|").append(NEW_LINE);

        for (LessonDetail lesson : lessonsForDay) {
            LessonHour lessonTime = lessonHoursMap.get(lesson.number());
            if (lessonTime == null) continue;

            String timeRange = lessonTime.start().substring(0, 5) + "-" + lessonTime.finish().substring(0, 5);
            String lessonType = lesson.groupTypeShortName() != null && !lesson.groupTypeShortName().isBlank()
                    ? lesson.groupTypeShortName() : "-";
            String location = formatLocation(lesson);

            sb.append("| ").append(timeRange).append(" | ")
              .append(lessonTime.displayNumber()).append(" | ")
              .append("**").append(escapeMarkdownTableContent(lesson.subjectName())).append("** | ")
              .append(lessonType).append(" | ")
              .append(location).append(" |").append(NEW_LINE);
        }
        sb.append(NEW_LINE);
    }

    private void appendDetailedLessons(StringBuilder sb, List<LessonDetail> lessonsForDay, Map<Integer, LessonHour> lessonHoursMap) {
        sb.append("### **Подробная информация**").append(NEW_LINE).append(NEW_LINE);

        int lessonIndex = 1;
        for (LessonDetail lesson : lessonsForDay) {
            LessonHour lessonTime = lessonHoursMap.get(lesson.number());
            if (lessonTime == null) {
                log.warn("Не найдено время для пары номер {} (предмет: {})", lesson.number(), lesson.subjectName());
                continue;
            }

            appendLessonCard(sb, lesson, lessonTime, lessonIndex++);
        }
    }

    private void appendLessonCard(StringBuilder sb, LessonDetail lesson, LessonHour lessonTime, int index) {
        String timeRange = lessonTime.start().substring(0, 5) + " - " + lessonTime.finish().substring(0, 5);

        sb.append("#### ").append(index).append(". **").append(escapeMarkdown(lesson.subjectName())).append("**").append(NEW_LINE);
        sb.append("> **Время:** ").append(timeRange).append(" (").append(lessonTime.displayNumber()).append(" пара)").append(NEW_LINE);

        if (lesson.groupTypeShortName() != null && !lesson.groupTypeShortName().isBlank()) {
            sb.append("> **Тип занятия:** ").append(lesson.groupTypeShortName()).append(NEW_LINE);
        }

        if (lesson.tutorName() != null && !lesson.tutorName().trim().isEmpty()) {
            sb.append("> **Преподаватель:** ").append(escapeMarkdown(lesson.tutorName().trim())).append(NEW_LINE);
        }

        String location = formatLocationDetailed(lesson);
        if (!location.equals("-")) {
            sb.append("> **Место проведения:** ").append(location).append(NEW_LINE);
        }

        sb.append(NEW_LINE);
    }

    private String formatLocation(LessonDetail lesson) {
        if (lesson.onlineClass()) {
            return "Онлайн";
        }

        if (lesson.auditory() != null && !lesson.auditory().isBlank()) {
            String location = lesson.auditory();
            if (lesson.building() != null && !lesson.building().isBlank()) {
                location += " (" + lesson.building() + ")";
            }
            return escapeMarkdownTableContent(location);
        }

        return "-";
    }

    private String formatLocationDetailed(LessonDetail lesson) {
        if (lesson.onlineClass()) {
            return "💻 **Онлайн занятие**";
        }

        if (lesson.auditory() != null && !lesson.auditory().isBlank()) {
            StringBuilder location = new StringBuilder();
            location.append("🚪 Аудитория ").append(escapeMarkdown(lesson.auditory()));
            if (lesson.building() != null && !lesson.building().isBlank()) {
                location.append(" (").append(escapeMarkdown(lesson.building())).append(")");
            }
            return location.toString();
        }

        return "-";
    }

    private String getLocalizedDayName(DayOfWeek dayOfWeek, Locale locale) {
        return dayOfWeek.getDisplayName(TextStyle.FULL, locale);
    }

    private String getLocalizedDayNameFromApiKey(String dayApiKey, Locale locale) {
        try {
            int dayValue = Integer.parseInt(dayApiKey);
            DayOfWeek dayOfWeek = DayOfWeek.of(dayValue);
            return getLocalizedDayName(dayOfWeek, locale);
        } catch (Exception e) {
            log.warn("Не удалось преобразовать ключ дня '{}' в DayOfWeek", dayApiKey);
            return "День " + dayApiKey;
        }
    }

    private String escapeMarkdownTableContent(String text) {
        if (text == null) return "";
        return text.replace("\n", " ").replace("\r", "").replace("|", "\\|");
    }

    private String escapeMarkdown(String text) {
        if (text == null) return "";
        return text.replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("#", "\\#");
    }
}
