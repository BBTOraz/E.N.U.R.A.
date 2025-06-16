package bbt.tao.orchestra.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record ExtractedDateResponse(
        @JsonPropertyDescription("Извлеченная дата в формате ДД.ММ.ГГГГ. Если дата не может быть определена или отсутствует в запросе, это поле должно быть null или отсутствовать.")
        @JsonProperty("extracted_date")
        String extractedDate,

        @JsonPropertyDescription("Булево значение. Установите true, если дата НЕ была найдена или не может быть однозначно определена из запроса пользователя. В этом случае поле extracted_date должно быть null.")
        @JsonProperty("is_date_not_found")
        boolean isDateNotFound
) {
    public static ExtractedDateResponse found(String date) {
        return new ExtractedDateResponse(date, false);
    }

    public static ExtractedDateResponse notFound() {
        return new ExtractedDateResponse(null, true);
    }
}