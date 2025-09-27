package bbt.tao.orchestra.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerificationResult {
    @JsonProperty("ok")
    private boolean ok;

    @JsonProperty("reasons")
    private List<String> reasons = new ArrayList<>();

    @JsonProperty("requiredChanges")
    private String requiredChanges;

    public VerificationResult() {
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
    }

    public void setRequiredChanges(String requiredChanges) {
        this.requiredChanges = requiredChanges;
    }
}

