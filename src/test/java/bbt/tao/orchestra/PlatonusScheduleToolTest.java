package bbt.tao.orchestra;

import bbt.tao.orchestra.dto.RegexParseOutput;
import bbt.tao.orchestra.dto.ScheduleFormatData;
import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusApiResponse;
import bbt.tao.orchestra.dto.enu.platonus.schedule.Timetable;
import bbt.tao.orchestra.service.DateResolutionService;
import bbt.tao.orchestra.service.client.PlatonusPortalApiClient;
import bbt.tao.orchestra.tools.enu.ScheduleTool;
import bbt.tao.orchestra.tools.formatter.fabric.ResponseFormatterRegistry;
import bbt.tao.orchestra.tools.formatter.impl.PlatonusScheduleResponseFormatter;
import bbt.tao.orchestra.util.AcademicTime;
import bbt.tao.orchestra.util.LLMDateParser;
import bbt.tao.orchestra.util.RegexProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;

import static java.util.Calendar.MONDAY;
import static java.util.Calendar.TUESDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PlatonusScheduleToolTest {

}
