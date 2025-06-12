package bbt.tao.orchestra.dto.enu.platonus;

import java.util.Map;

public record DayEntry(
        Map<String, LessonSlot> lessons
) {}
