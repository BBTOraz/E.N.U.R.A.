package bbt.tao.orchestra.dto.syntez;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TtsRequestDto {
    private String text;
    @JsonProperty("voice_sample_path")
    private String voiceSamplePath;
    private String language;
}
