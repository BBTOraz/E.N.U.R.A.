package bbt.tao.orchestra.dto.enu.platonus.schedule;

public record LessonDetail(
        int number,
        String studyGroupName,
        String tutorName,
        String auditory,
        String building,
        String subjectName,
        String groupTypeShortName,
        boolean onlineClass
        // List<Object> links
) {}
