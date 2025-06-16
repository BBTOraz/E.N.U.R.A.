package bbt.tao.orchestra.dto.enu.platonus.grades;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubjectGradeAggregation {
    private String tk1TotalScore;      // ТК1 ОБЩ.
    private String rk1Score;           // РК1
    private String r1Score;            // Р1
    private String tk2TotalScore;      // ТК2 ОБЩ.
    private String rk2Score;           // РК2
    private String r2Score;            // Р2
    private String courseworkGrade;    // Оценка за курсовую работу
    private String practiceGrade;      // Практика
    private String researchGrade;      // Исследоват. работа
    private String admissionRating;    // Рейтинг допуска
    private String examGrade;          // Экзамен
    private String finalNumericGrade;  // Итоговая оценка (числовая)
    private String finalLetterGrade;   // Итоговая оценка (буквенная)
}
