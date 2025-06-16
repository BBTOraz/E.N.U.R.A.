package bbt.tao.orchestra.dto.enu.platonus.grades;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;
import java.util.ArrayList;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubjectPerformance {
    private String subjectName;
    private SubjectGradeAggregation aggregatedGrades; // Общие оценки по предмету
    private List<SubjectActivityDetails> activities = new ArrayList<>(); // Лекции, практики, СРО
}
