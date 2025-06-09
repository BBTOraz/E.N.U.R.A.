package bbt.tao.orchestra.dto.enu.portal;

import com.fasterxml.jackson.annotation.JsonProperty;

public record EnuStaffMember(
        @JsonProperty("uuid") String uuid,
        @JsonProperty("building") String building,
        @JsonProperty("changeDate") String changeDate,
        @JsonProperty("fullname_ru") String fullnameRu,
        @JsonProperty("iin") String iin,
        @JsonProperty("mail") String email,
        @JsonProperty("maternity_leave") Integer maternityLeave,
        @JsonProperty("phone_work") String phoneWork,
        @JsonProperty("phone_mobile") String phoneMobile,
        @JsonProperty("room") String room,
        @JsonProperty("sex") Integer sex,
        @JsonProperty("staff_postname") String staffPostName,
        @JsonProperty("staff_unitname") String staffUnitName,
        @JsonProperty("tutor_postname") String tutorPostName,
        @JsonProperty("tutor_unitname") String tutorUnitName
){}
