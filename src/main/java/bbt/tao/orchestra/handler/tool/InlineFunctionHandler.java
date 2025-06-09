package bbt.tao.orchestra.handler.tool;

public interface InlineFunctionHandler {
    String functionName();
    String handle(String json) throws Exception;
}
