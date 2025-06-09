package bbt.tao.orchestra.dto.enu.portal;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.annotation.Nullable;

public record DictionaryStaffInfoRequest(
        @JsonProperty(defaultValue = "staff")
        @Nullable
        String catalog,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("ИИН (Индивидуальный идентификационный номер) физического лица(гражданина РК)")
        String iin,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("Имя сотрудника для поиска. Например: Иван, Мария, Макпал.")
        String firstname,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("фамилия сотрудника")
        String lastname,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("отчество сотрудника, например: Иванович, Сериковна, Талгатулы, Талгатұлы, Талгатқызы, Талгаткызы.")
        String middlename,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("Офис (здание, корпус, библиотека, студенческий дом, спорт комплекс, филиал) сотрудника")
        String building,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("Кабинет сотрудника, например: 101, 102, 103, 104, 105, 106, 107, 108, 109, 110.")
        String room,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("Номер телефона сотрудника")
        String phone,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("логин сотрудника")
        String login,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("подразделение сотрудника")
        String staff_unitname,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("должность сотрудника")
        String staff_postname,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("научное подразделение")
        String tutor_unitname,
        @Nullable
        @JsonProperty
        @JsonPropertyDescription("академическая должность")
        String tutor_postname,
        @Nullable
        @JsonProperty(defaultValue = "0")
        Long fired,
        @Nullable
        @JsonProperty(defaultValue = "1")
        Long not_fired
){}
