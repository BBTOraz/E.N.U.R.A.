package bbt.tao.orchestra.tools.formatter;

public interface ToolResponseFormatter<T> {
    String format(T response);
}
