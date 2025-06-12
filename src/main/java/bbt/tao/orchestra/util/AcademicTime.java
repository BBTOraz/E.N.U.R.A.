package bbt.tao.orchestra.util;

import bbt.tao.orchestra.dto.enu.platonus.PlatonusScheduleRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;


@Component
@Slf4j
public class AcademicTime {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final LocalDate SEMESTER_1_START_2024 = LocalDate.parse("2024-09-02", DATE_FORMATTER);
    private static final LocalDate SEMESTER_1_FINISH_2024 = LocalDate.parse("2024-12-15", DATE_FORMATTER);

    private static final LocalDate SEMESTER_2_START_2025 = LocalDate.parse("2025-01-13", DATE_FORMATTER);
    private static final LocalDate SEMESTER_2_FINISH_2025 = LocalDate.parse("2025-04-27", DATE_FORMATTER);

    // TODO: Добавить логику для других учебных годов или сделать ее динамической.

    public PlatonusScheduleRequest getTermAndWeek(LocalDate date) {
        Integer term = null;
        int academicWeek;
        LocalDate currentSemesterStartDate = null;

        if (!date.isBefore(SEMESTER_1_START_2024) && !date.isAfter(SEMESTER_1_FINISH_2024)) {
            term = 1;
            currentSemesterStartDate = SEMESTER_1_START_2024;
        } else if (!date.isBefore(SEMESTER_2_START_2025) && !date.isAfter(SEMESTER_2_FINISH_2025)) {
            term = 2;
            currentSemesterStartDate = SEMESTER_2_START_2025;
        }

        if (term != null && currentSemesterStartDate != null) {
            long daysBetween = ChronoUnit.DAYS.between(currentSemesterStartDate, date);
            int weekNumber = (int) (daysBetween / 7) + 1;
            academicWeek = Math.max(1, Math.min(15, weekNumber)); // Гарантируем 1-15
            return new PlatonusScheduleRequest(term, academicWeek);
        } else {
            log.warn("Дата {} находится вне известных периодов семестров. Не удалось определить term и week.", date);
            return null; // или выбросить исключение, или вернуть специальный объект ошибки
        }
    }

    public PlatonusScheduleRequest getCurrentTermAndWeek() {
        return getTermAndWeek(LocalDate.now());
    }
}
