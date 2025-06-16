package bbt.tao.orchestra.dto.enu.platonus.grades;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Grade {
    private String controlPoint;
    private String value;
    private boolean isNotProvided;

    public Grade(String controlPoint, String value) {
        this(controlPoint, value, false);
    }
}
