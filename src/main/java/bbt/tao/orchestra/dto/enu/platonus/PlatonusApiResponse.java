package bbt.tao.orchestra.dto.enu.platonus;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record PlatonusApiResponse(
        @JsonProperty("lessonHours") List<LessonHour> lessonHours,
        @JsonProperty("timetable") Timetable timetable
) {}