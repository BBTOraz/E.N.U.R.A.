package bbt.tao.orchestra.dto.enu.platonus.grades;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SubjectActivityDetails {
    private String activityName;
    private String teacher;
    private List<Grade> weeklyGrades1_7 = new ArrayList<>();
    private String tk1Score;
    private List<Grade> weeklyGrades8_15 = new ArrayList<>();
    private String tk2Score;
}
