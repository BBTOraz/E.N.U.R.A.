package bbt.tao.orchestra.tools.enu;

import bbt.tao.orchestra.dto.enu.portal.DictionaryStaffInfoRequest;
import bbt.tao.orchestra.dto.enu.portal.EnuStaffSearchResponse;
import bbt.tao.orchestra.service.client.EnuPortalApiClient;
import bbt.tao.orchestra.tools.formatter.fabric.ResponseFormatterRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

@Component
public class StaffTool {
    private final EnuPortalApiClient enuPortalApiClient;
    private final ResponseFormatterRegistry responseFormatterRegistry;

    public StaffTool(EnuPortalApiClient enuPortalApiClient, ResponseFormatterRegistry responseFormatterRegistry) {
        this.enuPortalApiClient = enuPortalApiClient;
        this.responseFormatterRegistry = responseFormatterRegistry;
    }

    @Tool(name = "getStaffInfo", description = """
            Ищет сотрудников в университете по параметрам:
                    - firstname: имя
                    - lastname: фамилия (все фамилии обычно заканчиваются на "ов" или "ев", "ова" или "ева")
                    - middlename: отчество (все отчества обычно заканчиваются на "ович" или "евич", "овна" или "евна", "улы(ұлы)" или "кызы(қызы)")
            Использовать только тогда, когда просят найти сотрудника, преподавателя, декана, учителя все термины связанные с преподаванием и поиском.
            """,
            returnDirect = true
    )
    public String getStaffInfo(DictionaryStaffInfoRequest request) {

        return responseFormatterRegistry.getFormatter(EnuStaffSearchResponse.class)
                .format(enuPortalApiClient.getStaffInfo(request));
    }
}
