package bbt.tao.orchestra.tools.formatter.fabric;

import bbt.tao.orchestra.tools.formatter.ToolResponseFormatter;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.ResolvableType;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ResponseFormatterRegistry {
    private final Map<Class<?>, ToolResponseFormatter<?>>  fmtType;


    public ResponseFormatterRegistry(List<ToolResponseFormatter<?>> list) {
        Map<Class<?>, ToolResponseFormatter<?>> map = new HashMap<>();
        for (ToolResponseFormatter<?> formatter : list) {
            ResolvableType rt = ResolvableType.forClass(formatter.getClass())
                .as(ToolResponseFormatter.class);
            Class<?> responseType = rt.getGeneric(0).resolve();
            Objects.requireNonNull(responseType,
                    () -> "Не удалось определить тип ответа для " + formatter.getClass());
            map.put(responseType, formatter);
        }
        this.fmtType = Collections.unmodifiableMap(map);
    }

    @SuppressWarnings("unchecked")
    public <T> ToolResponseFormatter<T> getFormatter(Class<T> type) {
        ToolResponseFormatter<?> formatter = fmtType.get(type);
        if (formatter == null) {
            throw new IllegalArgumentException("No formatter found for type: " + type.getName());
        }
        return (ToolResponseFormatter<T>) formatter;
    }
}
