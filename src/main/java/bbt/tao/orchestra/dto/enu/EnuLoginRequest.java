package bbt.tao.orchestra.dto.enu;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EnuLoginRequest(
        String json,
        @JsonProperty("auth_username") String username,
        @JsonProperty("auth_password") String password
) {
}
