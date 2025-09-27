package bbt.tao.orchestra.dto.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrchestraResponse {
    private String full;    // Полный ответ (LLM или tool)
    private String summary; // Краткое содержание
}

