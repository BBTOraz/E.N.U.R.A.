package bbt.tao.orchestra.dto;

public enum ResolvedQueryType {
    SPECIFIC_DAY_REQUESTED,         // Пользователь запросил конкретный день (завтра, пн, 09.06)
    SPECIFIC_WEEK_REQUESTED_BY_USER, // Пользователь явно указал номер недели
    CURRENT_WEEK_DEFAULT,           // По умолчанию используется текущая неделя
    ERROR_INVALID_WEEK_NUMBER,      // Пользователь указал невалидный номер недели
    ERROR_DATE_OUT_OF_SEMESTER,     // Дата попала вне известных семестров
    ERROR_GENERIC                   // Общая ошибка разрешения
}
