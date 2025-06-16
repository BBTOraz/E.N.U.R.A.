package bbt.tao.orchestra.dto.enu.platonus.schedule;

import java.util.Map;

public record Timetable(
        Map<String, DayEntry> days
) {}
