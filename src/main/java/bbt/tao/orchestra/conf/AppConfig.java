package bbt.tao.orchestra.conf;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

@Configuration
public class AppConfig {
    @Bean
    public Clock systemClock() {
        return Clock.system(ZoneId.systemDefault());
    }
}
