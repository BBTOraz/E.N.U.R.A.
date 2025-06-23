package bbt.tao.orchestra;


import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusApiResponse;
import bbt.tao.orchestra.service.client.PlatonusPortalApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "platonus.api.base-url=https://edu.enu.kz",
        "enu.api.username=021109550741",
        "enu.api.password=Saoaoggo1lol@",
        "logging.level.bbt.tao.orchestra.service.client=TRACE",
        "logging.level.org.springframework.web.reactive.function.client.ExchangeFunctions=TRACE"
})
class PlatonusPortalApiClientIntegrationTest {

    @Autowired
    private PlatonusPortalApiClient apiClient;

    @Test
    void testFetchTimetable_variousTermWeek_combinations() throws ExecutionException, InterruptedException {
        // набор комбинаций term/week
        int[][] combos = {
                {1, 1},   // 1-й семестр, 1-я неделя
                {1, 5},   // 1-й семестр, 5-я неделя
                {2, 9},   // 2-й семестр, 9-я неделя
                {2, 15},  // 2-й семестр, последняя неделя
                {3, 3}    // «несуществующий» семестр — убедиться, что API отвечает пустым или дефолтным телом
        };

        for (int[] combo : combos) {
            int term = combo[0], week = combo[1];
            // .block() чтобы тест ждал завершения
            PlatonusApiResponse response = apiClient.fetchTimetable(term, week);
            // Проверяем, что мы вообще получили какой-то объект (может быть пустой)
            assertThat(response)
                    .as("Проверяем, что fetchTimetable(term=%d, week=%d) не вернул null", term, week)
                    .isNotNull();

            // Дополнительно можно вывести в консоль или в лог
            System.out.println(String.format(
                    "→ term=%d week=%d : lessons=%s",
                    term,
                    week,
                    response.timetable() == null ? "null" : response.timetable().days().keySet()
            ));

            System.out.println("Response: " + response);
        }
    }
}

