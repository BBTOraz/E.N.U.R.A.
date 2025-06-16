package bbt.tao.orchestra.dto.enu.platonus.grades;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentSemesterPerformance {
    private String academicYear;
    private String term;
    private String studentId;
    private List<SubjectPerformance> subjects = new ArrayList<>();
    private List<String> parsingIssues = new ArrayList<>();
}
