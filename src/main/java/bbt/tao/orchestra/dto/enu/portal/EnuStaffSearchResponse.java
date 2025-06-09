package bbt.tao.orchestra.dto.enu.portal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EnuStaffSearchResponse(
        @JsonProperty("output") List<EnuStaffMember> members
) {}

