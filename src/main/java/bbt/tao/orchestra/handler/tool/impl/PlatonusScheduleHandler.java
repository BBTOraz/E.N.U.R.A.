package bbt.tao.orchestra.handler.tool.impl;

import bbt.tao.orchestra.handler.tool.InlineFunctionHandler;
import bbt.tao.orchestra.tools.enu.ScheduleTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class PlatonusScheduleHandler implements InlineFunctionHandler {
    private final ScheduleTool scheduleTool;
    private final ObjectMapper mapper;

    public PlatonusScheduleHandler(ScheduleTool scheduleTool, ObjectMapper mapper) {
        this.scheduleTool = scheduleTool;
        this.mapper = mapper;
    }

    @Override
    public String functionName() {
        return "getPlatonusStudentSchedule";
    }

    @Override
    public String handle(String json) throws Exception {
        JsonNode root = mapper.readTree(json);

        String userInput;
        if (root.has("userInput")) {
            userInput = root.get("userInput").asText();
        } else {
            userInput = root.toString();
        }

        return scheduleTool.getSchedule(userInput);
    }
}
