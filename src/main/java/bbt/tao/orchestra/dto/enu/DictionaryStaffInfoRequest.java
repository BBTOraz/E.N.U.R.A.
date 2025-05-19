package bbt.tao.orchestra.dto.enu;


import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.annotation.Nullable;

public record DictionaryStaffInfoRequest(
        @JsonProperty(defaultValue = "staff")
        @JsonPropertyDescription("Каталог для поиска. По умолчанию staff")
        String catalog,
        @Nullable
        @JsonPropertyDescription("ИИН (Индивидуальный идентификационный номер) физического лица(гражданина РК)")
        String iin,
        @Nullable
        @JsonPropertyDescription("Имя сотрудника для поиска. Например: Иван, Мария, Макпал.")
        String firstname,
        @Nullable
        @JsonPropertyDescription("фамилия сотрудника")
        String lastname,
        @Nullable
        @JsonPropertyDescription("отчество сотрудника, например: Иванович, Сериковна, Талгатулы, Талгатұлы, Талгатқызы, Талгаткызы.")
        String middlename,
        @Nullable
        @JsonPropertyDescription("Офис (здание, корпус, библиотека, студенческий дом, спорт комплекс, филиал) сотрудника")
        String building,
        @Nullable
        @JsonPropertyDescription("Кабинет сотрудника, например: 101, 102, 103, 104, 105, 106, 107, 108, 109, 110.")
        String room,
        @Nullable
        @JsonPropertyDescription("Номер телефона сотрудника")
        String phone,
        @Nullable
        @JsonPropertyDescription("логин сотрудника")
        String login,
        @Nullable
        @JsonPropertyDescription("подразделение сотрудника")
        String staff_unitname,
        @Nullable
        @JsonPropertyDescription("должность сотрудника")
        String staff_postname,
        @Nullable
        @JsonPropertyDescription("научное подразделение")
        String tutor_unitname,
        @Nullable
        @JsonPropertyDescription("академическая должность")
        String tutor_postname,
        @JsonProperty(defaultValue = "0")
        Long fired,
        @JsonProperty(defaultValue = "1")
        Long not_fired
){}
