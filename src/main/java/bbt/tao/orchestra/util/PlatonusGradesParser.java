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
    private static final int COL_SUBJECT_NAME = 0;
    private static final int COL_SUBJECT_ACTIVITY_STREAM_1 = 1; // Для первой активности в строке tr.subject
    private static final int COL_SUBJECT_TEACHER_1 = 2;         // Для первой активности
    private static final int COL_SUBJECT_WEEK_1_START = 3;      // Начало оценок 1-7 недель для первой активности
    private static final int COL_SUBJECT_TK1_ACTIVITY_1 = 10;   // ТК1 для первой активности
    private static final int COL_SUBJECT_TK1_TOTAL = 11;        // ТК1 ОБЩ. (для предмета)
    private static final int COL_SUBJECT_RK1 = 12;              // РК1 (для предмета)
    private static final int COL_SUBJECT_R1 = 13;               // Р1 (для предмета)
    private static final int COL_SUBJECT_WEEK_8_START = 14;     // Начало оценок 8-15 недель для первой активности
    private static final int COL_SUBJECT_TK2_ACTIVITY_1 = 24;   // ТК2 для первой активности
    private static final int COL_SUBJECT_TK2_TOTAL = 25;        // ТК2 ОБЩ. (для предмета)
    private static final int COL_SUBJECT_RK2 = 26;              // РК2 (для предмета)
    private static final int COL_SUBJECT_R2 = 27;               // Р2 (для предмета)
    private static final int COL_SUBJECT_COURSEWORK = 28;
    private static final int COL_SUBJECT_PRACTICE = 29;
    private static final int COL_SUBJECT_RESEARCH = 30;
    private static final int COL_SUBJECT_ADMISSION_RATING = 31;
    private static final int COL_SUBJECT_EXAM = 32;
    private static final int COL_SUBJECT_FINAL_NUMERIC = 33;
    private static final int COL_SUBJECT_FINAL_LETTER = 34;
    private static final int ACT_IDX_STREAM = 0;
    private static final int ACT_IDX_TEACHER = 1;
    private static final int ACT_IDX_W1_START = 2;
    private static final int ACT_IDX_TK1 = ACT_IDX_W1_START + 7;
    private static final int ACT_IDX_W8_START = ACT_IDX_TK1 + 1;
    private static final int ACT_IDX_TK2 = ACT_IDX_W8_START + 8;


    public StudentSemesterPerformance parse(String htmlContent, String studentId, String academicYear, String term) {
        log.info("Начало парсинга оценок для студента {}, год {}, семестр {}", studentId, academicYear, term);
        Document doc = Jsoup.parse(htmlContent);
        StudentSemesterPerformance performance = new StudentSemesterPerformance(academicYear, term, studentId, new ArrayList<>(), new ArrayList<>());

        Element gradesTableBody = doc.selectFirst("table.table-compact-2x.bordered > tbody");
        if (gradesTableBody == null) {
            log.warn("Тело таблицы с оценками (tbody) не найдено.");
            performance.getParsingIssues().add("Основная таблица оценок не найдена.");
            return performance;
        }

        Elements rows = gradesTableBody.children();
        SubjectPerformance currentSubjectPerformance = null;

        for (Element row : rows) {
            if (row.hasClass("subject")) {
                // Завершаем и добавляем предыдущий предмет, если он был
                if (currentSubjectPerformance != null) {
                    performance.getSubjects().add(currentSubjectPerformance);
                }

                // Начинаем новый предмет
                Elements cells = row.children();
                if (cells.size() <= COL_SUBJECT_FINAL_LETTER) { // Проверка на минимальное количество ячеек для главной строки
                    String msg = "Строка предмета (class='subject') содержит недостаточно ячеек (" + cells.size() + "). Пропускаем.";
                    log.warn(msg);
                    performance.getParsingIssues().add(msg + " HTML: " + row.text().substring(0, Math.min(row.text().length(), 70)));
                    currentSubjectPerformance = null; // Сбрасываем, чтобы не добавлять активности к неполному предмету
                    continue;
                }

                String subjectName = safeGetText(cells, COL_SUBJECT_NAME);
                SubjectGradeAggregation aggregatedGrades = new SubjectGradeAggregation(
                        safeGetText(cells, COL_SUBJECT_TK1_TOTAL),
                        safeGetText(cells, COL_SUBJECT_RK1),
                        safeGetText(cells, COL_SUBJECT_R1),
                        safeGetText(cells, COL_SUBJECT_TK2_TOTAL),
                        safeGetText(cells, COL_SUBJECT_RK2),
                        safeGetText(cells, COL_SUBJECT_R2),
                        safeGetText(cells, COL_SUBJECT_COURSEWORK),
                        safeGetText(cells, COL_SUBJECT_PRACTICE),
                        safeGetText(cells, COL_SUBJECT_RESEARCH),
                        safeGetText(cells, COL_SUBJECT_ADMISSION_RATING),
                        safeGetText(cells, COL_SUBJECT_EXAM),
                        safeGetText(cells, COL_SUBJECT_FINAL_NUMERIC),
                        safeGetText(cells, COL_SUBJECT_FINAL_LETTER)
                );
                currentSubjectPerformance = new SubjectPerformance(subjectName, aggregatedGrades, new ArrayList<>());
                log.debug("Парсинг предмета: {}", subjectName);

                // Парсим первую активность (из строки tr.subject)
                SubjectActivityDetails firstActivity = new SubjectActivityDetails();
                firstActivity.setActivityName(safeGetText(cells, COL_SUBJECT_ACTIVITY_STREAM_1));
                firstActivity.setTeacher(safeGetText(cells, COL_SUBJECT_TEACHER_1));

                List<Grade> weekly1_7_first = new ArrayList<>();
                for (int i = 0; i < 7; i++) weekly1_7_first.add(parseGradeFromCell(cells, COL_SUBJECT_WEEK_1_START + i, String.valueOf(i + 1)));
                firstActivity.setWeeklyGrades1_7(weekly1_7_first);
                firstActivity.setTk1Score(safeGetText(cells, COL_SUBJECT_TK1_ACTIVITY_1));

                List<Grade> weekly8_15_first = new ArrayList<>();
                for (int i = 0; i < 8; i++) weekly8_15_first.add(parseGradeFromCell(cells, COL_SUBJECT_WEEK_8_START + i, String.valueOf(i + 8)));
                firstActivity.setWeeklyGrades8_15(weekly8_15_first);
                firstActivity.setTk2Score(safeGetText(cells, COL_SUBJECT_TK2_ACTIVITY_1));

                currentSubjectPerformance.getActivities().add(firstActivity);

            } else if (row.hasClass("subject_study_groups") && currentSubjectPerformance != null) {
                // Дополнительная активность для текущего предмета
                Elements cells = row.children();
                if (cells.size() <= ACT_IDX_TK2) { // Минимальное ожидаемое количество ячеек
                    String msg = "Строка активности (class='subject_study_groups') содержит недостаточно ячеек (" + cells.size() + "). Пропускаем.";
                    log.warn(msg);
                    performance.getParsingIssues().add(msg + " HTML: " + row.text().substring(0, Math.min(row.text().length(), 70)));
                    continue;
                }

                SubjectActivityDetails activityDetails = new SubjectActivityDetails();
                activityDetails.setActivityName(safeGetText(cells, ACT_IDX_STREAM));
                activityDetails.setTeacher(safeGetText(cells, ACT_IDX_TEACHER));

                List<Grade> weekly1_7 = new ArrayList<>();
                for (int i = 0; i < 7; i++) weekly1_7.add(parseGradeFromCell(cells, ACT_IDX_W1_START + i, String.valueOf(i + 1)));
                activityDetails.setWeeklyGrades1_7(weekly1_7);
                activityDetails.setTk1Score(safeGetText(cells, ACT_IDX_TK1));

                List<Grade> weekly8_15 = new ArrayList<>();
                for (int i = 0; i < 8; i++) weekly8_15.add(parseGradeFromCell(cells, ACT_IDX_W8_START + i, String.valueOf(i + 8)));
                activityDetails.setWeeklyGrades8_15(weekly8_15);
                activityDetails.setTk2Score(safeGetText(cells, ACT_IDX_TK2));

                currentSubjectPerformance.getActivities().add(activityDetails);
            }
        }

        if (currentSubjectPerformance != null) {
            performance.getSubjects().add(currentSubjectPerformance);
        }

        if (performance.getSubjects().isEmpty() && performance.getParsingIssues().isEmpty()){
            performance.getParsingIssues().add("Не найдено ни одного предмета в таблице успеваемости.");
        }
        log.info("Парсинг завершен. Извлечено {} предметов. Обнаружено {} проблем при парсинге.",
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
        log.warn("Попытка прочитать ячейку с индексом {} для '{}', но ячеек всего {}. Возвращаем пустую оценку.", cellIndex, controlPointName, cells.size());
        return new Grade(controlPointName, "", true); // Если ячейки нет, считаем "не предусмотрено"
    }

    private String safeGetText(Elements cells, int index) {
        if (index >= 0 && index < cells.size()) {
            return cells.get(index).text().trim();
        }
        return "";
    }
}
