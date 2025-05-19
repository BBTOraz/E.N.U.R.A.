package bbt.tao.orchestra.conf;

import bbt.tao.orchestra.dto.enu.DictionaryStaffInfoRequest;
import bbt.tao.orchestra.dto.enu.EnuStaffSearchResponse;
import bbt.tao.orchestra.service.client.EnuApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.function.Function;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class StaffToolConfig {

    private final EnuApiClient enuApiClient;

    public StaffToolConfig(EnuApiClient enuApiClient) {
        this.enuApiClient = enuApiClient;
    }


    @Bean(name = "getStaffInfo")
    @Description("""
            Ищет сотрудников в университете по параметрам, если запрос связан с поисками информации о сотрудниках, то всегда используй эту функцию:
                    - firstname: имя
                    - lastname: фамилия (все фамилии обычно заканчиваются на "ов" или "ев", "ова" или "ева")
                    - middlename: отчество (все отчества обычно заканчиваются на "ович" или "евич", "овна" или "евна", "улы(ұлы)" или "кызы(қызы)")
            """)
    public Function<DictionaryStaffInfoRequest, EnuStaffSearchResponse> getStaffInfo(){
        return request -> {
            log.info("Requested getStaffInfo tool {}", request);
            return enuApiClient.getStaffInfo(request)
                    .block();
        };
    }

}
