package bbt.tao.orchestra.dto.enu.platonus;

import bbt.tao.orchestra.dto.enu.platonus.grades.StudentSemesterPerformance;

public record FormattedGradesResponse(
        StudentSemesterPerformance semesterPerformance, // Данные об оценках
        String recommendations,         // Строка для рекомендаций от LLM (пока заглушка)
        String scholarshipStatusInfo
) {
}
