package bbt.tao.orchestra.handler.tool;

import reactor.core.publisher.Mono;

public interface InlineFunctionHandler {
    String functionName();
    String handle(String json) throws Exception;
}
