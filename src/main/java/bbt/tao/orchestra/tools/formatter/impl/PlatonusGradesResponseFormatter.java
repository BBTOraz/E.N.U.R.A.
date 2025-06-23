package bbt.tao.orchestra.tools.formatter.impl;

import bbt.tao.orchestra.dto.enu.platonus.FormattedGradesResponse;
import bbt.tao.orchestra.dto.enu.platonus.grades.*;
import bbt.tao.orchestra.tools.formatter.ToolResponseFormatter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class PlatonusGradesResponseFormatter implements ToolResponseFormatter<FormattedGradesResponse> {

    private static final String NEW_LINE = "\n";
    private static final String TABLE_SEPARATOR = "|---|---|---|---|---|\n";
    private static final String SECTION_SEPARATOR = "\n---\n";


    @Override
    public String format(FormattedGradesResponse responseData) {
        if (responseData == null || responseData.semesterPerformance() == null) {
            return "Не удалось получить данные об оценках для форматирования.";
        }

        StudentSemesterPerformance sg = responseData.semesterPerformance();
        StringBuilder md = new StringBuilder();

        // Заголовок
        md.append("### 📊 Оценки за ").append(escapeMarkdownTableContent(sg.getAcademicYear()))
                .append(" учебный год, ").append(escapeMarkdownTableContent(sg.getTerm()))
                .append(" семестр (Студент ID: ")
                .append(escapeMarkdownTableContent(sg.getStudentId() != null ? sg.getStudentId() : "N/A")).append(")")
                .append(NEW_LINE).append(NEW_LINE);

        if (sg.getParsingIssues() != null && !sg.getParsingIssues().isEmpty()) {
            md.append("**Обнаружены проблемы при обработке страницы с оценками:**").append(NEW_LINE);
            for (String issue : sg.getParsingIssues()) {
                md.append("- ").append(escapeMarkdownGeneral(issue)).append(NEW_LINE);
            }
            md.append(NEW_LINE);
            if (sg.getSubjects() == null || sg.getSubjects().isEmpty()) {
                md.append("Данные об успеваемости по предметам не могут быть отображены из-за указанных проблем.").append(NEW_LINE);
                return md.toString();
            }
        }

        if (sg.getSubjects() == null || sg.getSubjects().isEmpty()) {
            md.append("Оценки по предметам за этот период не найдены.").append(NEW_LINE);
        } else {
            md.append("| Дисциплина | Допуск | Экзамен | Итог (%) | Итог (Буква) |").append(NEW_LINE);
            md.append(TABLE_SEPARATOR);
            for (SubjectPerformance subjectPerf : sg.getSubjects()) {
                SubjectGradeAggregation agg = subjectPerf.getAggregatedGrades();
                md.append("| **").append(escapeMarkdownTableContent(subjectPerf.getSubjectName())).append("** | ");
                md.append(escapeMarkdownTableContent(formatScore(agg.getAdmissionRating()))).append(" | ");
                md.append(escapeMarkdownTableContent(formatScore(agg.getExamGrade()))).append(" | ");
                md.append(escapeMarkdownTableContent(formatScore(agg.getFinalNumericGrade()))).append(" | ");
                md.append(escapeMarkdownTableContent(agg.getFinalLetterGrade())).append(" |").append(NEW_LINE);
            }
            md.append(NEW_LINE);

            md.append(SECTION_SEPARATOR);
            md.append("### Детализация по дисциплинам:").append(NEW_LINE).append(NEW_LINE);

            for (SubjectPerformance subjectPerf : sg.getSubjects()) {
                SubjectGradeAggregation agg = subjectPerf.getAggregatedGrades();
                md.append("#### **").append(escapeMarkdownGeneral(subjectPerf.getSubjectName())).append("**").append(NEW_LINE);

                md.append("- **РК1:** ").append(escapeMarkdownGeneral(formatScore(agg.getRk1Score())))
                        .append(" (Рейтинг 1: ").append(escapeMarkdownGeneral(formatScore(agg.getR1Score()))).append(")");
                md.append(" | **ТК1 Общий:** ").append(escapeMarkdownGeneral(formatScore(agg.getTk1TotalScore()))).append(NEW_LINE);

                md.append("- **РК2:** ").append(escapeMarkdownGeneral(formatScore(agg.getRk2Score())))
                        .append(" (Рейтинг 2: ").append(escapeMarkdownGeneral(formatScore(agg.getR2Score()))).append(")");
                md.append(" | **ТК2 Общий:** ").append(escapeMarkdownGeneral(formatScore(agg.getTk2TotalScore()))).append(NEW_LINE);

                if (isProvided(agg.getCourseworkGrade())) md.append("- Курсовая работа: ").append(escapeMarkdownGeneral(formatScore(agg.getCourseworkGrade()))).append(NEW_LINE);
                if (isProvided(agg.getPracticeGrade())) md.append("- Практика: ").append(escapeMarkdownGeneral(formatScore(agg.getPracticeGrade()))).append(NEW_LINE);
                if (isProvided(agg.getResearchGrade())) md.append("- Исследовательская работа: ").append(escapeMarkdownGeneral(formatScore(agg.getResearchGrade()))).append(NEW_LINE);
                md.append(NEW_LINE);

                if (subjectPerf.getActivities() != null && !subjectPerf.getActivities().isEmpty()) {
                    for (SubjectActivityDetails activity : subjectPerf.getActivities()) {
                        md.append("  **Вид занятия:** `").append(escapeMarkdownGeneral(activity.getActivityName())).append("`");
                        if (activity.getTeacher() != null && !activity.getTeacher().isBlank()){
                            md.append(" (Преподаватель: ").append(escapeMarkdownGeneral(activity.getTeacher())).append(")");
                        }
                        md.append(NEW_LINE);
                        md.append("    - ТК1 (активность): ").append(escapeMarkdownGeneral(formatScore(activity.getTk1Score()))).append(NEW_LINE);
                        md.append("    - ТК2 (активность): ").append(escapeMarkdownGeneral(formatScore(activity.getTk2Score()))).append(NEW_LINE);

                        if (hasValidWeeklyGrades(activity.getWeeklyGrades1_7())) {
                            md.append("    - Недели 1-7: ").append(formatWeeklyGrades(activity.getWeeklyGrades1_7(), 1, 7)).append(NEW_LINE);
                        }
                        if (hasValidWeeklyGrades(activity.getWeeklyGrades8_15())) {
                            md.append("    - Недели 8-15: ").append(formatWeeklyGrades(activity.getWeeklyGrades8_15(), 8, 15)).append(NEW_LINE);
                        }
                        md.append(NEW_LINE);
                    }
                } else {
                    md.append("  *Детализация по видам занятий отсутствует.*").append(NEW_LINE).append(NEW_LINE);
                }
                md.append(SECTION_SEPARATOR);
            }
        }

        if (responseData.recommendations() != null && !responseData.recommendations().isBlank()) {
            md.append(NEW_LINE).append("**💡 Рекомендации:**").append(NEW_LINE);
            // Предполагаем, что рекомендации уже в Markdown или просто текст, который нужно обернуть
            md.append(responseData.recommendations()).append(NEW_LINE);
        }

        if (responseData.scholarshipStatusInfo() != null && !responseData.scholarshipStatusInfo().isBlank()) {
            md.append(NEW_LINE).append("**🎓 Стипендия:**").append(NEW_LINE);
            md.append(responseData.scholarshipStatusInfo()).append(NEW_LINE);
        }
        return md.toString();
    }

    private String formatScore(String score) {
        return (score == null || score.trim().isEmpty()) ? "-" : score.trim();
    }

    private boolean isProvided(String score){
        return score != null && !score.trim().isEmpty() && !"-".equals(score.trim());
    }

    private String formatWeeklyGrades(List<Grade> weeklyGrades, int startWeek, int endWeek) {
        if (weeklyGrades == null) return "-";
        String gradesString = weeklyGrades.stream()
                .filter(item -> {
                    try {
                        if (item.getControlPoint() == null || !item.getControlPoint().matches("\\d+")) return false;
                        int weekNum = Integer.parseInt(item.getControlPoint());
                        return weekNum >= startWeek && weekNum <= endWeek;
                    } catch (NumberFormatException e) { return false; }
                })
                .map(item -> {
                    String scoreDisplay = item.getValue();
                    if (item.isNotProvided()) scoreDisplay = "н.п.";
                    else if (scoreDisplay == null || scoreDisplay.trim().isEmpty()) scoreDisplay = "(-)";
                    else if (scoreDisplay.equalsIgnoreCase("н")) scoreDisplay = "н";
                    return "`" + item.getControlPoint() + "`: " + escapeMarkdownGeneral(scoreDisplay); // Используем общий escape
                })
                .collect(Collectors.joining(", "));
        return gradesString.isEmpty() ? "нет данных" : gradesString;
    }

    private boolean hasValidWeeklyGrades(List<Grade> weeklyGrades) {
        if (weeklyGrades == null || weeklyGrades.isEmpty()) return false;
        return weeklyGrades.stream()
                .anyMatch(item -> (item.getValue() != null && !item.getValue().trim().isEmpty() && !item.getValue().trim().equals("-")) || item.isNotProvided());
    }

    // Экранирование для содержимого ячеек таблицы (особенно важно для `|`)
    private String escapeMarkdownTableContent(String text) {
        if (text == null) return "";
        return text.replace("|", "\\|");
        // Можно добавить .replace("\n", "<br>") если хотите переносы внутри ячеек, но это уже ближе к HTML в Markdown
    }

    // Более общее экранирование для текста вне таблиц (заголовки, списки)
    private String escapeMarkdownGeneral(String text) {
        if (text == null) return "";
        return text
                .replace("*", "\\*")
                .replace("_", "\\_")
                .replace("`", "\\`")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("#", "\\#"); // Экранируем #, если он может быть в начале строки данных
    }
}