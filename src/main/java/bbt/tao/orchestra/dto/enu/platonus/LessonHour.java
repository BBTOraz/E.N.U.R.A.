package bbt.tao.orchestra.dto.enu.platonus;

public record LessonHour(
        int number,
        String start,
        String finish,
        int shiftNumber,
        int displayNumber,
        int duration
) {}
