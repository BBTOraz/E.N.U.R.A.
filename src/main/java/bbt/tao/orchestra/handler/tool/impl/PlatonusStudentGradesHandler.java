package bbt.tao.orchestra.handler.tool.impl;

import bbt.tao.orchestra.handler.tool.InlineFunctionHandler;
import bbt.tao.orchestra.tools.enu.PlatonusStudentGradesTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Slf4j
@Component
public class PlatonusStudentGradesHandler implements InlineFunctionHandler {

    private final PlatonusStudentGradesTool studentGradesTool;

    public PlatonusStudentGradesHandler(PlatonusStudentGradesTool studentGradesTool) {
        this.studentGradesTool = studentGradesTool;
    }

    @Override
    public String functionName() {
        return "getGrades";
    }

    @Override
    public String handle(String jsonArguments) throws ExecutionException, InterruptedException {
        log.info("StudentGradesHandler: вызван для функции '{}'. Аргументы (ожидаются пустыми): {}",
                functionName(), jsonArguments);
        return studentGradesTool.getGrades();
    }
}
