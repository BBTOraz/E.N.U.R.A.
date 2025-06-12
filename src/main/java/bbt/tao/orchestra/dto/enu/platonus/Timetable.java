package bbt.tao.orchestra.dto.enu.platonus;

import java.util.Map;

public record Timetable(
        Map<String, DayEntry> days
) {}
