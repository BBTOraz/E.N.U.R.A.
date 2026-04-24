package bbt.tao.orchestra.tools.formatter.impl;

import bbt.tao.orchestra.dto.enu.platonus.FormattedGradesResponse;
import bbt.tao.orchestra.dto.enu.platonus.grades.*;
import bbt.tao.orchestra.tools.formatter.ToolResponseFormatter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Component
public class PlatonusGradesResponseFormatter implements ToolResponseFormatter<FormattedGradesResponse> {

    private static final String NEW_LINE = "\n";
    private static final String DIVIDER = "---";
    private static final String EMOJI_BOOK = "📚";
    private static final String EMOJI_CHART = "📊";
    private static final String EMOJI_CALENDAR = "📅";
    private static final String EMOJI_TEACHER = "👨‍🏫";
    private static final String EMOJI_WARNING = "⚠️";
    private static final String EMOJI_STAR = "⭐";
    private static final String EMOJI_CHECK = "✅";

    @Override
    public String format(FormattedGradesResponse responseData) {
        if (responseData == null || responseData.semesterPerformance() == null) {
            return "❌ Не удалось получить данные об оценках для форматирования.";
        }

        StudentSemesterPerformance performance = responseData.semesterPerformance();
        StringBuilder md = new StringBuilder();

        // Главный заголовок с информацией о семестре
        appendHeader(md, performance);

        // Показываем проблемы парсинга, если есть
        appendParsingIssues(md, performance.getParsingIssues());

        // Сводная таблица по всем предметам
        appendSummaryTable(md, performance.getSubjects());

        // Детальная информация по каждому предмету
        appendDetailedSubjects(md, performance.getSubjects());

        return md.toString();
    }

    private void appendHeader(StringBuilder md, StudentSemesterPerformance performance) {
        md.append("# ").append(EMOJI_CHART).append(" Академические результаты").append(NEW_LINE).append(NEW_LINE);

        // Информационная карточка
        md.append("> ").append(EMOJI_CALENDAR).append(" **Период:** ")
          .append(escapeMarkdown(performance.getAcademicYear())).append(" учебный год, ")
          .append(escapeMarkdown(performance.getTerm())).append(" семестр").append(NEW_LINE);

        if (performance.getStudentId() != null && !performance.getStudentId().trim().isEmpty()) {
            md.append("> ").append("🆔 **ID студента:** ")
              .append(escapeMarkdown(performance.getStudentId())).append(NEW_LINE);
        }

        md.append("> ").append(EMOJI_BOOK).append(" **Всего дисциплин:** ")
          .append(performance.getSubjects() != null ? performance.getSubjects().size() : 0)
          .append(NEW_LINE).append(NEW_LINE);
    }

    private void appendParsingIssues(StringBuilder md, List<String> parsingIssues) {
        if (parsingIssues != null && !parsingIssues.isEmpty()) {
            md.append("## ").append(EMOJI_WARNING).append(" Проблемы при обработке данных").append(NEW_LINE).append(NEW_LINE);
            for (String issue : parsingIssues) {
                md.append("- ").append(EMOJI_WARNING).append(" ").append(escapeMarkdown(issue)).append(NEW_LINE);
            }
            md.append(NEW_LINE);
        }
    }

    private void appendSummaryTable(StringBuilder md, List<SubjectPerformance> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            md.append("## ").append(EMOJI_WARNING).append(" Данные не найдены").append(NEW_LINE).append(NEW_LINE);
            md.append("Оценки по предметам за этот период не найдены.").append(NEW_LINE);
            return;
        }

        md.append("## ").append(EMOJI_STAR).append(" **СВОДНАЯ ТАБЛИЦА РЕЗУЛЬТАТОВ**").append(NEW_LINE).append(NEW_LINE);

        // Улучшенная таблица с цветовыми индикаторами
        md.append("| № | Дисциплина | Допуск | Экзамен | Итог (баллов) | Буква | Статус |").append(NEW_LINE);
        md.append("|:---:|:---|:---:|:---:|:---:|:---:|:---:|").append(NEW_LINE);

        int index = 1;
        for (SubjectPerformance subject : subjects) {
            SubjectGradeAggregation agg = subject.getAggregatedGrades();
            String status = getGradeStatus(agg.getFinalLetterGrade());

            md.append("| ").append(index++).append(" | ")
              .append("**").append(escapeMarkdownTableContent(subject.getSubjectName())).append("** | ")
              .append(formatScoreWithStatus(agg.getAdmissionRating())).append(" | ")
              .append(formatScoreWithStatus(agg.getExamGrade())).append(" | ")
              .append(formatScoreWithStatus(agg.getFinalNumericGrade())).append(" | ")
              .append("**").append(formatScore(agg.getFinalLetterGrade())).append("** | ")
              .append(status).append(" |").append(NEW_LINE);
        }
        md.append(NEW_LINE);
    }

    private void appendDetailedSubjects(StringBuilder md, List<SubjectPerformance> subjects) {
        if (subjects == null || subjects.isEmpty()) return;

        md.append("## ").append(EMOJI_BOOK).append(" **ДЕТАЛЬНАЯ ИНФОРМАЦИЯ ПО ДИСЦИПЛИНАМ**").append(NEW_LINE).append(NEW_LINE);

        int subjectIndex = 1;
        for (SubjectPerformance subject : subjects) {
            appendSubjectDetails(md, subject, subjectIndex++);
        }
    }

    private void appendSubjectDetails(StringBuilder md, SubjectPerformance subject, int index) {
        SubjectGradeAggregation agg = subject.getAggregatedGrades();

        md.append("### ").append(index).append(". **").append(escapeMarkdown(subject.getSubjectName())).append("**").append(NEW_LINE).append(NEW_LINE);

        // Основные оценки в виде карточек
        appendGradeCards(md, agg);

        // Дополнительные оценки (если есть)
        appendAdditionalGrades(md, agg);

        // Детали по видам деятельности
        appendActivityDetails(md, subject.getActivities());

        md.append(DIVIDER).append(NEW_LINE).append(NEW_LINE);
    }

    private void appendGradeCards(StringBuilder md, SubjectGradeAggregation agg) {
        md.append("#### **Рубежные контроли**").append(NEW_LINE).append(NEW_LINE);

        // Табличный вид для рубежных контролей
        md.append("| Период | ТК общий | РК | Рейтинг |").append(NEW_LINE);
        md.append("|:---|:---:|:---:|:---:|").append(NEW_LINE);
        md.append("| **Первый рубежный контроль** | ")
          .append(formatScore(agg.getTk1TotalScore())).append(" | ")
          .append(formatScore(agg.getRk1Score())).append(" | ")
          .append(formatScore(agg.getR1Score())).append(" |").append(NEW_LINE);
        md.append("| **Второй рубежный контроль** | ")
          .append(formatScore(agg.getTk2TotalScore())).append(" | ")
          .append(formatScore(agg.getRk2Score())).append(" | ")
          .append(formatScore(agg.getR2Score())).append(" |").append(NEW_LINE).append(NEW_LINE);

        // Итоговые оценки
        md.append("#### **Итоговые результаты**").append(NEW_LINE).append(NEW_LINE);
        md.append("| Допуск | Экзамен | Итог (%) | Итог (Буква) |").append(NEW_LINE);
        md.append("|:---:|:---:|:---:|:---:|").append(NEW_LINE);
        md.append("| ").append(formatScoreWithEmoji(agg.getAdmissionRating())).append(" | ")
          .append(formatScoreWithEmoji(agg.getExamGrade())).append(" | ")
          .append(formatScoreWithEmoji(agg.getFinalNumericGrade())).append(" | ")
          .append("**").append(formatScore(agg.getFinalLetterGrade())).append("** |").append(NEW_LINE).append(NEW_LINE);
    }

    private void appendAdditionalGrades(StringBuilder md, SubjectGradeAggregation agg) {
        boolean hasAdditional = hasValue(agg.getCourseworkGrade()) ||
                               hasValue(agg.getPracticeGrade()) ||
                               hasValue(agg.getResearchGrade());

        if (hasAdditional) {
            md.append("#### **Дополнительные оценки**").append(NEW_LINE).append(NEW_LINE);

            if (hasValue(agg.getCourseworkGrade())) {
                md.append("- **Курсовая работа:** `").append(formatScore(agg.getCourseworkGrade())).append("`").append(NEW_LINE);
            }
            if (hasValue(agg.getPracticeGrade())) {
                md.append("- **Практика:** `").append(formatScore(agg.getPracticeGrade())).append("`").append(NEW_LINE);
            }
            if (hasValue(agg.getResearchGrade())) {
                md.append("- **Исследовательская работа:** `").append(formatScore(agg.getResearchGrade())).append("`").append(NEW_LINE);
            }
            md.append(NEW_LINE);
        }
    }

    private void appendActivityDetails(StringBuilder md, List<SubjectActivityDetails> activities) {
        if (activities == null || activities.isEmpty()) return;

        md.append("#### **Детализация по видам занятий**").append(NEW_LINE).append(NEW_LINE);

        for (SubjectActivityDetails activity : activities) {
            md.append("##### **").append(escapeMarkdown(activity.getActivityName())).append("**").append(NEW_LINE);

            if (activity.getTeacher() != null && !activity.getTeacher().trim().isEmpty()) {
                md.append("> ").append(EMOJI_TEACHER).append(" **Преподаватель:** ")
                  .append(escapeMarkdown(activity.getTeacher())).append(NEW_LINE).append(NEW_LINE);
            }

            // ТК оценки для активности
            md.append("**Текущий контроль:**").append(NEW_LINE);
            md.append("- ТК1: `").append(formatScore(activity.getTk1Score())).append("`")
              .append(" | ТК2: `").append(formatScore(activity.getTk2Score())).append("`").append(NEW_LINE).append(NEW_LINE);

            // Недельные оценки
            appendWeeklyGrades(md, activity.getWeeklyGrades1_7(), "Недели 1-7");
            appendWeeklyGrades(md, activity.getWeeklyGrades8_15(), "Недели 8-15");

            md.append(NEW_LINE);
        }
    }

    private void appendWeeklyGrades(StringBuilder md, List<Grade> weeklyGrades, String period) {
        if (!hasValidWeeklyGrades(weeklyGrades)) return;

        md.append("**").append(period).append(":**").append(NEW_LINE);

        Map<String, String> gradeMap = weeklyGrades.stream()
                .collect(Collectors.toMap(
                        Grade::getControlPoint,
                        item -> {
                            if (item.isNotProvided()) return "н.п.";
                            String value = item.getValue();
                            if (value == null || value.trim().isEmpty()) return "-";
                            return value.trim();
                        },
                        (v1, v2) -> v1
                ));

        md.append("| ");
        int startWeek = period.contains("1-7") ? 1 : 8;
        int endWeek = period.contains("1-7") ? 7 : 15;

        IntStream.rangeClosed(startWeek, endWeek).forEach(week ->
                md.append(week).append(" | ")
        );
        md.append(NEW_LINE);

        md.append("|");
        IntStream.rangeClosed(startWeek, endWeek).forEach(week ->
                md.append(":---:|")
        );
        md.append(NEW_LINE);

        md.append("| ");
        IntStream.rangeClosed(startWeek, endWeek).forEach(week -> {
            String grade = gradeMap.getOrDefault(String.valueOf(week), "-");
            md.append(formatWeeklyGrade(grade)).append(" | ");
        });
        md.append(NEW_LINE).append(NEW_LINE);
    }

    // Utility methods
    private String formatScore(String score) {
        return (score == null || score.trim().isEmpty()) ? "-" : score.trim();
    }

    private String formatScoreWithStatus(String score) {
        if (score == null || score.trim().isEmpty()) return "-";
        String trimmed = score.trim();
        try {
            double value = Double.parseDouble(trimmed);
            if (value >= 90) return trimmed + " " + EMOJI_STAR;
            if (value >= 75) return trimmed + " " + EMOJI_CHECK;
            if (value < 50) return trimmed + " " + EMOJI_WARNING;
        } catch (NumberFormatException e) {
            // Не число, возвращаем как есть
        }
        return trimmed;
    }

    private String formatScoreWithEmoji(String score) {
        if (score == null || score.trim().isEmpty()) return "-";
        String trimmed = score.trim();
        try {
            double value = Double.parseDouble(trimmed);
            if (value >= 90) return "🟢 " + trimmed;
            if (value >= 75) return "🟡 " + trimmed;
            if (value >= 50) return "🟠 " + trimmed;
            return "🔴 " + trimmed;
        } catch (NumberFormatException e) {
            return trimmed;
        }
    }

    private String formatWeeklyGrade(String grade) {
        if (grade.equals("н.п.") || grade.equals("н")) return grade;
        if (grade.equals("-")) return "-";
        try {
            double value = Double.parseDouble(grade);
            if (value >= 90) return "🟢 " + grade;
            if (value >= 75) return "🟡 " + grade;
            if (value >= 50) return "🟠 " + grade;
            return "🔴 " + grade;
        } catch (NumberFormatException e) {
            return grade;
        }
    }

    private String getGradeStatus(String letterGrade) {
        if (letterGrade == null || letterGrade.trim().isEmpty()) return "-";
        String grade = letterGrade.trim().toUpperCase();
        switch (grade) {
            case "A": case "A-": return "🟢 Отлично";
            case "B+": case "B": case "B-": return "🟡 Хорошо";
            case "C+": case "C": case "C-": return "🟠 Удовлетв.";
            case "D+": case "D": return "🔴 Слабо";
            case "F": return "❌ Неудовл.";
            default: return grade;
        }
    }

    private boolean hasValue(String value) {
        return value != null && !value.trim().isEmpty() && !"-".equals(value.trim());
    }

    private boolean hasValidWeeklyGrades(List<Grade> weeklyGrades) {
        if (weeklyGrades == null || weeklyGrades.isEmpty()) return false;
        return weeklyGrades.stream()
                .anyMatch(item -> (item.getValue() != null && !item.getValue().trim().isEmpty() && !item.getValue().trim().equals("-")) || item.isNotProvided());
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

