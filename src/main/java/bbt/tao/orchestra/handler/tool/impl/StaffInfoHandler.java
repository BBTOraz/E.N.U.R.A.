package bbt.tao.orchestra.handler.tool.impl;

import bbt.tao.orchestra.dto.enu.portal.DictionaryStaffInfoRequest;
import bbt.tao.orchestra.handler.tool.InlineFunctionHandler;
import bbt.tao.orchestra.tools.enu.StaffTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;


@Component
public class StaffInfoHandler implements InlineFunctionHandler {
    private final StaffTool staffTool;
    private final ObjectMapper mapper;

    public StaffInfoHandler(StaffTool staffTool, ObjectMapper mapper) {
        this.staffTool = staffTool;
        this.mapper = mapper;
    }

    //todo ВАЖНО ИСПРАВИТЬ! ЖЕСТКИЙ ХАРДКОД ИМЕНИ ТУЛА, НУЖНО ПОЛУЧИТЬ ИЗ КОНТЕКСТА
    @Override
    public String functionName() {
        return "getStaffInfo";
    }

    @Override
    public String handle(String json) throws Exception {
        JsonNode node = mapper.readTree(json);
        JsonNode req = node.has("request") ? node.get("request") : node;
        DictionaryStaffInfoRequest dto =
                mapper.treeToValue(req, DictionaryStaffInfoRequest.class);

        return staffTool.getStaffInfo(dto.firstname(), dto.lastname(), dto.middlename());
    }
}