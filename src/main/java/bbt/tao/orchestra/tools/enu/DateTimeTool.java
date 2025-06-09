package bbt.tao.orchestra.tools.enu;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@Component
public class DateTimeTool {


    @Tool(name = "getCurrentDateTime", description = "Возвращает текущую дату и время в формате ISO 8601.")
    public String getCurrentDateTime() {
        return java.time.LocalDateTime.now().toString();
    }
}
