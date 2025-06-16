package bbt.tao.orchestra;

import bbt.tao.orchestra.dto.enu.platonus.grades.StudentSemesterPerformance;
import bbt.tao.orchestra.service.client.PlatonusPortalApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class GradeTest {
    @Autowired
    private PlatonusPortalApiClient client;

    @Test
    void fetchAndParseGrades_integration() {
        int academicYear = 2024;
        int term = 1;
        String lang = "ru";
        String studentIdOverride = null; // not used in current implementation

        // Выполняем интеграционный запрос и ожидаем реальные данные
        Mono<StudentSemesterPerformance> resultMono = client.fetchAndParseGrades(academicYear, term, lang, studentIdOverride);
        StudentSemesterPerformance performance = resultMono.block();

        System.out.println("Performance: " + performance);
        // Проверяем, что ответ не null и содержит данные или, по крайней мере, корректно заполненный объект
        assertNotNull(performance, "Performance should not be null");
        assertEquals(String.valueOf(academicYear), performance.getAcademicYear());
        assertEquals(String.valueOf(term), performance.getTerm());
        assertNotNull(performance.getStudentId(), "Student ID should be present");

        boolean hasGrades = performance.getSubjects() != null && !performance.getSubjects().isEmpty();
        boolean hasErrors = performance.getParsingIssues() != null && !performance.getParsingIssues().isEmpty();
        assertTrue(hasGrades || hasErrors, "Performance should contain either grades or errors");

        // Для отладки: выводим результат в консоль
        System.out.println("StudentSemesterPerformance: " + performance);
    }
}
