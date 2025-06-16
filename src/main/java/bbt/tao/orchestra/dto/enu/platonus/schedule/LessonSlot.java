package bbt.tao.orchestra.dto.enu.platonus.schedule;

import java.util.List;

public record LessonSlot(
        List<LessonDetail> lessons
) {}
