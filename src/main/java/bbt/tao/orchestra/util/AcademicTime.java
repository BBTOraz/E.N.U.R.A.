package bbt.tao.orchestra.util;

import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusScheduleRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.time.temporal.ChronoUnit;


@Component
@Slf4j
public class AcademicTime {
    private final Clock clock;

    @Value("${academic.year.start.month:9}")
    private int academicYearStartMonthValue;
    @Value("${academic.semester1.start.month:9}")
    private int semester1StartMonth;
    @Value("${academic.semester1.start.day:2}")
    private int semester1StartDay;
    @Value("${academic.semester1.finish.month:12}")
    private int semester1FinishMonth;
    @Value("${academic.semester1.finish.day:15}")
    private int semester1FinishDay;

    @Value("${academic.semester2.start.month:1}")
    private int semester2StartMonth;
    @Value("${academic.semester2.start.day:13}")
    private int semester2StartDay;
    @Value("${academic.semester2.finish.month:4}")
    private int semester2FinishMonth;
    @Value("${academic.semester2.finish.day:27}")
    private int semester2FinishDay;

    public AcademicTime(Clock clock) {
        this.clock = clock;
    }

    public int getSemester1StartMonthDefault() { return semester1StartMonth; }
    public int getSemester1StartDayDefault() { return semester1StartDay; }


    private int getRelevantAcademicYearStartForDate(LocalDate date) {
        Month academicStartMonth = Month.of(academicYearStartMonthValue);
        if (date.getMonthValue() >= academicStartMonth.getValue()) {
            return date.getYear();
        } else {
            return date.getYear() - 1;
        }
    }

    public PlatonusScheduleRequest getTermAndWeek(LocalDate date) {
        int relevantAcademicYear = getRelevantAcademicYearStartForDate(date);

        LocalDate sem1StartDate = LocalDate.of(relevantAcademicYear, semester1StartMonth, semester1StartDay);
        LocalDate sem1FinishDate = LocalDate.of(relevantAcademicYear, semester1FinishMonth, semester1FinishDay);
        LocalDate sem2StartDate = LocalDate.of(relevantAcademicYear + 1, semester2StartMonth, semester2StartDay);
        LocalDate sem2FinishDate = LocalDate.of(relevantAcademicYear + 1, semester2FinishMonth, semester2FinishDay);

        Integer term = null;
        LocalDate currentSemesterStartDate = null;

        if (!date.isBefore(sem1StartDate) && !date.isAfter(sem1FinishDate)) {
            term = 1;
            currentSemesterStartDate = sem1StartDate;
        } else if (!date.isBefore(sem2StartDate) && !date.isAfter(sem2FinishDate)) {
            term = 2;
            currentSemesterStartDate = sem2StartDate;
        }

        if (term != null && currentSemesterStartDate != null) {
            long daysBetween = ChronoUnit.DAYS.between(currentSemesterStartDate, date);
            int weekNumber = (int) (daysBetween / 7) + 1;
            int academicWeek = Math.max(1, Math.min(15, weekNumber));
            log.debug("Для даты {}: учебный год {}, семестр {}, начало {}, неделя {}",
                    date, relevantAcademicYear, term, currentSemesterStartDate, academicWeek);
            return new PlatonusScheduleRequest(term, academicWeek);
        } else {
            log.warn("Дата {} не попадает в известные периоды семестров для учебного года {}.",
                    date, relevantAcademicYear);
            return null;
        }
    }

    public PlatonusScheduleRequest getCurrentTermAndWeek() {
        return getTermAndWeek(LocalDate.now(clock));
    }

    public PlatonusScheduleRequest getContextualTermAndWeek() {
        LocalDate currentDate = LocalDate.now(clock);
        PlatonusScheduleRequest current = getTermAndWeek(currentDate);
        if (current != null) {
            return current;
        }
        log.debug("Текущая дата {} в межсезонье. Контекстный семестр будет term=1 (поведение API).", currentDate);
        return new PlatonusScheduleRequest(1, 1);
    }
}
