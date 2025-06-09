package bbt.tao.orchestra.dto.enu.portal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EnuPortalLoginRequest(
        String json,
        @JsonProperty("auth_username") String username,
        @JsonProperty("auth_password") String password
) {
}
