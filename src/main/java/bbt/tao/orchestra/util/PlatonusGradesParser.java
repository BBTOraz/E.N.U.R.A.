package bbt.tao.orchestra.util;

import bbt.tao.orchestra.dto.enu.platonus.grades.*;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class PlatonusGradesParser {
    private static final int IDX_S_DISCIPLINE = 0;
    private static final int IDX_S_STREAM_MAIN = 1;
    private static final int IDX_S_TEACHER_MAIN = 2;
    private static final int IDX_S_W_START_1 = 3; // Недели 1-7 (7 ячеек: 3-9)
    private static final int IDX_S_TK1_ACTIVITY = 10;
    private static final int IDX_S_TK1_TOTAL_SUBJECT = 11;
    private static final int IDX_S_RK1_SUBJECT = 12;
    private static final int IDX_S_R1_SUBJECT = 13;
    private static final int IDX_S_W_START_2 = 14; // Недели 8-15 (8 ячеек: 14-21)
    private static final int IDX_S_TK2_ACTIVITY = 22;
    private static final int IDX_S_TK2_TOTAL_SUBJECT = 23;
    private static final int IDX_S_RK2_SUBJECT = 24;
    private static final int IDX_S_R2_SUBJECT = 25;
    private static final int IDX_S_COURSEWORK = 26;
    private static final int IDX_S_PRACTICE = 27;
    private static final int IDX_S_RESEARCH = 28;
    private static final int IDX_S_ADMISSION_RATING = 29;
    private static final int IDX_S_EXAM = 30;
    private static final int IDX_S_FINAL_NUMERIC = 31;
    private static final int IDX_S_FINAL_LETTER = 32;
    private static final int EXPECTED_CELLS_SUBJECT_ROW = 33;

    private static final int IDX_A_STREAM = 0;
    private static final int IDX_A_TEACHER = 1;
    private static final int IDX_A_W_START_1 = 2;
    private static final int IDX_A_TK1 = 9;
    private static final int IDX_A_W_START_2 = 10;
    private static final int IDX_A_TK2 = 18;
    private static final int EXPECTED_CELLS_ACTIVITY_ROW = 19;

    public StudentSemesterPerformance parse(String htmlContent, String studentId, String academicYear, String term) {
        log.info("Начало парсинга оценок для студента {}, год {}, семестр {}", studentId, academicYear, term);
        Document doc = Jsoup.parse(htmlContent);
        StudentSemesterPerformance performance = new StudentSemesterPerformance(academicYear, term, studentId, new ArrayList<>(), new ArrayList<>());

        Element gradesTableBody = doc.selectFirst("table.table-compact-2x.bordered > tbody");
        if (gradesTableBody == null) {
            String msg = "Тело таблицы с оценками (tbody) не найдено.";
            log.warn(msg);
            performance.getParsingIssues().add(msg);
            return performance;
        }

        Elements rows = gradesTableBody.children();
        SubjectPerformance currentSubjectPerformance = null;
        for (Element row : rows) {
            if (row.hasClass("subject")) {
                if (currentSubjectPerformance != null) {
                    performance.getSubjects().add(currentSubjectPerformance);
                }
                Elements cells = row.children();
                if (cells.size() < EXPECTED_CELLS_SUBJECT_ROW) {
                    String msg = String.format("Строка предмета (class='subject') содержит %d ячеек, ожидалось %d. Пропускаем.", cells.size(), EXPECTED_CELLS_SUBJECT_ROW);
                    log.warn(msg + " HTML: " + row.text().substring(0, Math.min(row.text().length(), 70)));
                    performance.getParsingIssues().add(msg);
                    currentSubjectPerformance = null;
                    continue;
                }

                String subjectName = safeGetText(cells, IDX_S_DISCIPLINE);
                SubjectGradeAggregation aggregatedGrades = new SubjectGradeAggregation(
                        safeGetText(cells, IDX_S_TK1_TOTAL_SUBJECT), safeGetText(cells, IDX_S_RK1_SUBJECT),
                        safeGetText(cells, IDX_S_R1_SUBJECT), safeGetText(cells, IDX_S_TK2_TOTAL_SUBJECT),
                        safeGetText(cells, IDX_S_RK2_SUBJECT), safeGetText(cells, IDX_S_R2_SUBJECT),
                        safeGetText(cells, IDX_S_COURSEWORK), safeGetText(cells, IDX_S_PRACTICE),
                        safeGetText(cells, IDX_S_RESEARCH), safeGetText(cells, IDX_S_ADMISSION_RATING),
                        safeGetText(cells, IDX_S_EXAM), safeGetText(cells, IDX_S_FINAL_NUMERIC),
                        safeGetText(cells, IDX_S_FINAL_LETTER)
                );
                currentSubjectPerformance = new SubjectPerformance(subjectName, aggregatedGrades, new ArrayList<>());
                log.debug("Парсинг предмета: {}", subjectName);

                SubjectActivityDetails firstActivity = new SubjectActivityDetails();
                firstActivity.setActivityName(safeGetText(cells, IDX_S_STREAM_MAIN));
                firstActivity.setTeacher(safeGetText(cells, IDX_S_TEACHER_MAIN));
                List<Grade> weekly1_7_first = new ArrayList<>();
                for (int i = 0; i < 7; i++) weekly1_7_first.add(parseGradeFromCell(cells, IDX_S_W_START_1 + i, String.valueOf(i + 1)));
                firstActivity.setWeeklyGrades1_7(weekly1_7_first);
                firstActivity.setTk1Score(safeGetText(cells, IDX_S_TK1_ACTIVITY));
                List<Grade> weekly8_15_first = new ArrayList<>();
                for (int i = 0; i < 8; i++) weekly8_15_first.add(parseGradeFromCell(cells, IDX_S_W_START_2 + i, String.valueOf(i + 8)));
                firstActivity.setWeeklyGrades8_15(weekly8_15_first);
                firstActivity.setTk2Score(safeGetText(cells, IDX_S_TK2_ACTIVITY));
                currentSubjectPerformance.getActivities().add(firstActivity);

            } else if (row.hasClass("subject_study_groups") && currentSubjectPerformance != null) {
                Elements cells = row.children();
                if (cells.size() < EXPECTED_CELLS_ACTIVITY_ROW) {
                    String msg = String.format("Строка активности (class='subject_study_groups') содержит %d ячеек, ожидалось %d. Пропускаем.", cells.size(), EXPECTED_CELLS_ACTIVITY_ROW);
                    log.warn(msg + " HTML: " + row.text().substring(0, Math.min(row.text().length(), 70)));
                    performance.getParsingIssues().add(msg);
                    continue;
                }

                SubjectActivityDetails activityDetails = new SubjectActivityDetails();
                activityDetails.setActivityName(safeGetText(cells, IDX_A_STREAM));
                activityDetails.setTeacher(safeGetText(cells, IDX_A_TEACHER));
                List<Grade> weekly1_7 = new ArrayList<>();
                for (int i = 0; i < 7; i++) weekly1_7.add(parseGradeFromCell(cells, IDX_A_W_START_1 + i, String.valueOf(i + 1)));
                activityDetails.setWeeklyGrades1_7(weekly1_7);
                activityDetails.setTk1Score(safeGetText(cells, IDX_A_TK1));
                List<Grade> weekly8_15 = new ArrayList<>();
                for (int i = 0; i < 8; i++) weekly8_15.add(parseGradeFromCell(cells, IDX_A_W_START_2 + i, String.valueOf(i + 8)));
                activityDetails.setWeeklyGrades8_15(weekly8_15);
                activityDetails.setTk2Score(safeGetText(cells, IDX_A_TK2));
                currentSubjectPerformance.getActivities().add(activityDetails);
            }
        }

        if (currentSubjectPerformance != null) {
            performance.getSubjects().add(currentSubjectPerformance);
        }

        if (performance.getSubjects().isEmpty() && performance.getParsingIssues().isEmpty()){
            performance.getParsingIssues().add("Не найдено ни одного предмета в таблице успеваемости.");
        }
        log.info("Парсинг HTML завершен. Извлечено {} предметов. Обнаружено {} проблем при парсинге.",
                performance.getSubjects().size(), performance.getParsingIssues().size());
        return performance;
    }

    private Grade parseGradeFromCell(Elements cells, int cellIndex, String controlPointName) {
        if (cellIndex >= 0 && cellIndex < cells.size()) {
            Element cell = cells.get(cellIndex);
            String scoreValue = cell.text().trim();
            boolean isNp = cell.attr("style").toLowerCase().contains("background-color: #bbbbbb");
            return new Grade(controlPointName, scoreValue, isNp);
        }
        // Если ячейка выходит за пределы, это проблема с нашими индексами или HTML-структурой
        log.warn("Попытка прочитать несуществующую ячейку: индекс {}, controlPoint '{}', всего ячеек {}. Возвращаем пустую оценку.", cellIndex, controlPointName, cells.size());
        return new Grade(controlPointName, "", true);
    }

    private String safeGetText(Elements cells, int index) {
        if (index >= 0 && index < cells.size()) {
            return cells.get(index).text().trim();
        }
        return "";
    }
}
