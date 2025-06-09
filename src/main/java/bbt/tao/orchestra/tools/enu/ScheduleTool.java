package bbt.tao.orchestra.tools.enu;


import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class ScheduleTool {

    @Tool(name = "getSchedule", description = """
            Возвращает  
            Используется для планирования задач или мероприятий.
            """)
    public Instant getSchedule(){
        return Instant.now()
                .plus(3, ChronoUnit.HOURS);
    }
}
