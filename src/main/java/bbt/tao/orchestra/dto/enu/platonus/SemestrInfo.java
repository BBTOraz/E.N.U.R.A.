package bbt.tao.orchestra.dto.enu.platonus;

import java.time.LocalDate;

public record SemestrInfo(
        int term, // 1 или 2
        int academicWeek, // 1-15
        LocalDate semesterStartDate,
        LocalDate semesterFinishDate,
        String semesterId
) {
}
