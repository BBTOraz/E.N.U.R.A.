package bbt.tao.orchestra.service.enu;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

public interface EnuService<Req, Res> {
    Mono<Res> find(Req request);
}
