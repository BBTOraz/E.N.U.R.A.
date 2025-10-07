package bbt.tao.orchestra.tools.enu;

import bbt.tao.orchestra.dto.enu.portal.DictionaryStaffInfoRequest;
import bbt.tao.orchestra.dto.enu.portal.EnuStaffSearchResponse;
import bbt.tao.orchestra.service.client.EnuPortalApiClient;
import bbt.tao.orchestra.tools.formatter.fabric.ResponseFormatterRegistry;
import bbt.tao.orchestra.tools.meta.AgentToolMeta;
import io.micrometer.observation.annotation.Observed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Component
@Slf4j
public class StaffTool {
    private final EnuPortalApiClient enuPortalApiClient;
    private final ResponseFormatterRegistry responseFormatterRegistry;


    public StaffTool(EnuPortalApiClient enuPortalApiClient,
                     ResponseFormatterRegistry responseFormatterRegistry) {
        this.enuPortalApiClient = enuPortalApiClient;
        this.responseFormatterRegistry = responseFormatterRegistry;
    }

    @AgentToolMeta(
            description = """
                    Получает сведения о сотруднике ENU: корректные ФИО, контакты, должности, научные степени и расписание приемных часов.
                    Поддерживает поиск по фамилии, имени и отчеству, учитывает склонения и возвращает структурированную карточку с контактными данными.
                    """,
            examples = {
                    "Найди контакты преподавателя Иванов Иван Иванович",
                    "Покажи приемные часы доцента Сарсенов Жандос"
            }
    )
    @Tool(name = "getStaffInfo", description = """
            Ты получаешь произвольный запрос пользователя (на русском или казахском), где может встречаться ФИО в любом падеже и порядке. Твоя цель — извлечь ФИО и привести его к канонической форме (именительный падеж), при этом:
            
            правильно восстанавливать род (м/ж) по имени/отчеству/казахскому отчества-суффиксу;
            
            корректно восстанавливать фамилию (женские — с «-а/-я», прилагательные — «-ская», мужские — базовая форма «-ов/-ев/-ин/-ский/-цкий» и др.);
            
            нормализовать отчество:
            русские — -ович/-евич (м), -овна/-евна (ж);
            казахские — -ұлы/-улы (м), -қызы/-кызы (ж) → нормализуй к «ұлы» и «қызы», если текст на кириллице.
            
            Игнорировать служебные слова и предмет запроса («найди», «почту», «email», «номер», «контакты», «декан», «преподаватель» и т.п.).
            
            Вызвать инструмент только с аргументами firstname, lastname, middlename. Если какое-то поле не обнаружено, не выдумывай, просто опусти.
            
            Правила нормализации (кратко).
            
            Определи компоненты ФИО и их порядок. Возможны шаблоны:
            Фамилия Имя Отчество, Имя Отчество Фамилия, Фамилия Имя, Имя Отчество, Имя Фамилия.
            Используй подсказки: окончания отчеств/родовые суффиксы, список типичных женских имён, казахские суффиксы ұлы/қызы.
            
            Приведи все части в именительный падеж:
            
            Имя: «Канагата» → «Канагат», «Асылхану» → «Асылхан».
            
            Отчество: «Абетовича» → «Абетович», «Галыбековну» → «Галыбековна», «Нұрланұлын» → «Нұрланұлы».
            
            Фамилия: снимай падеж («Жартыбаеву» → базовая «Жартыбаев»), затем скорректируй по роду:
            
            женские фамилии: «-ова/-ева/-ина/-ына»; прилагательные: «-ская»; сложные каз.-рус.: «-баева/-бекова/-галиева» и т.п.
            
            мужские: «-ов/-ев/-ин/-ын/-ский/-цкий/-дзе/-швили/-оглы/-ко/-иа/-иа» и др.
            (учти, некоторые фамилии неизменяемые: «Ким», «Ли», «Пак», «Тлеу», «Али», «Око», «Го», «Ян», «Чой», «-ко» — одинаковы для м/ж).
            
            Определение пола (приоритеты):
            
            суффикс отчества (-овна/-евна → жен., -ович/-евич → муж.; -қызы → жен., -ұлы → муж.);
            
            имя из частотных списков (Асем/Айжан/Мария → жен.; Канагат/Ерлан/Сергей → муж.);
            
            если по имени/отчеству неясно — не меняй родовую форму фамилии насильно.
            
            Гибридные/редкие случаи. Двойные фамилии сохраняй с дефисом, родовую коррекцию применяй к прилагательной части («Иванова-Петрова», «Сарыбай-Смирнова»). Не транслитерируй, не меняй раскладку.
            Если в запросе есть только имя+отчество без фамилии — передай то, что надёжно извлечено. Не выдумывай фамилию.
            Формат вызова инструмента.
            Возвращай только аргументы инструмента getStaffInfo в виде JSON:
            { "lastname": "...", "firstname": "...", "middlename": "..." }
            Поля, которые не удалось достоверно определить, опускай.
            Примеры (строго следуй образцу).
            
            Вход: «Найди почту Жартыбаеву Макпал Галыбековну»
            TOOL_ARGS: { "lastname": "Жартыбаева", "firstname": "Макпал", "middlename": "Галыбековна" }
            (Пояснение: «Жартыбаеву» → жен. «Жартыбаева» по имени «Макпал» и «-овну» → «-овна».)
            
            Вход: «Нужен телефон Дюсекеева Канагата Абетовича»
            TOOL_ARGS: { "lastname": "Дюсекеев", "firstname": "Канагат", "middlename": "Абетович" }
            
            Вход: «Покажи контакты Ивановой Марии Сергеевны»
            TOOL_ARGS: { "lastname": "Иванова", "firstname": "Мария", "middlename": "Сергеевна" }
            (Важно: «Ивановой» — это или Т.п. жен., или Р.п. муж.; по имени+отчеству выбираем жен.)
            
            Вход: «Email Серікқызы Асем»
            TOOL_ARGS: { "firstname": "Асем", "middlename": "Серікқызы" }
            (Фамилии нет — не придумываем.)
            
            Вход: «Найди Ахметұлы Еркебұлан из ВТиПО»
            TOOL_ARGS: { "firstname": "Еркебұлан", "middlename": "Ахметұлы" }
            
            Вход: «Контакты Ким Елена»
            TOOL_ARGS: { "lastname": "Ким", "firstname": "Елена" }
            («Ким» — неизменяемая фамилия, не добавляй «-а».)
            
            Вход: «Поиск Садыкова-Жумабек Айдану Ержановну»
            TOOL_ARGS: { "lastname": "Садыкова-Жумабек", "firstname": "Айдана", "middlename": "Ержановна" }
            (Снимай падежи: «Айдану» → «Айдана», «Ержановну» → «Ержановна».)
            
            Вход: «Найди преподавателя Поляк Анну Андреевну»
            TOOL_ARGS: { "lastname": "Поляк", "firstname": "Анна", "middlename": "Андреевна" }
            («Поляк» — неизменяемая фамилия; женск. окончание не добавляется.)
            
            Вход: «Найди Толеуова Алишера»
            TOOL_ARGS: { "lastname": "Толеуов", "firstname": "Алишер" }
            (Мужской род → фамилия «Толеуов».)
            
            Вход: «Почта Ермекова Асель»
            TOOL_ARGS: { "lastname": "Ермекова", "firstname": "Асель" }
            """,
            returnDirect = true
    )
    public String getStaffInfo(@ToolParam(description = "Имя", required = false) String firstname,
                               @ToolParam(description = "Фамилия", required = false) String lastname,
                               @ToolParam(description = "Отчество", required = false) String middlename) throws ExecutionException, InterruptedException {
        DictionaryStaffInfoRequest request = new DictionaryStaffInfoRequest(
                "staff",
                null,
                firstname,
                lastname,
                middlename,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L,
                1L
        );
        return responseFormatterRegistry.getFormatter(EnuStaffSearchResponse.class)
                .format(enuPortalApiClient.getStaffInfo(request));
    }
}
