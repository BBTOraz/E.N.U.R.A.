package bbt.tao.orchestra.service.enu.impl;

import bbt.tao.orchestra.dto.enu.DictionaryStaffInfoRequest;
import bbt.tao.orchestra.dto.enu.EnuLoginRequest;
import bbt.tao.orchestra.dto.enu.EnuStaffSearchResponse;
import bbt.tao.orchestra.service.client.EnuApiClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class EnuAuthService {

    private final EnuApiClient enuApiClient;

    @Value("${enu.api.username}")
    private String username;
    @Value("${enu.api.password}")
    private String password;

    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private Mono<Boolean> loginInProgressMono;

    public EnuAuthService(EnuApiClient enuApiClient) {
        this.enuApiClient = enuApiClient;
    }

    @PostConstruct
    public void initialLoginAttempt() {
        ensureLoggedIn().subscribe(
                success -> { if (Boolean.TRUE.equals(success)) log.info("Initial ENU Portal login successful via EnuAuthService, cookie acquired: {}", enuApiClient.getCookieValue() != null); },
                error -> log.error("Initial ENU Portal login failed via EnuAuthService.", error)
        );
    }

    public Mono<Boolean> ensureLoggedIn() {
        if (loggedIn.get() && enuApiClient.getCookieValue() != null)
            return Mono.just(true);

        synchronized (this) {
            if (loginInProgressMono == null || isMonoTerminated(loginInProgressMono)) {
                log.info("No active login attempt or previous attempt completed. Initiating new login.");
                EnuLoginRequest loginRequest = new EnuLoginRequest("1", username, password);
                loginInProgressMono = enuApiClient.login(loginRequest)
                        .doOnSuccess(success -> {
                            boolean isLoginSuccessful = Boolean.TRUE.equals(success) && enuApiClient.getCookieValue() != null;
                            loggedIn.set(isLoginSuccessful);
                            if (isLoginSuccessful) {
                                log.info("Login successful, cookie stored successfully");
                            } else {
                                log.warn("Login unsuccessful or cookie was not stored");
                            }
                        })
                        .doOnError(e -> {
                            loggedIn.set(false);
                            log.error("Login error", e);
                        })
                        .cache();
            } else {
                log.info("Login attempt already in progress. Subscribing to existing Mono.");
            }
        }
        return loginInProgressMono;
    }

    private boolean isMonoTerminated(Mono<?> mono) {
        try {
            mono.block(java.time.Duration.ofMillis(1));
            return true; // Завершился (успешно или с ошибкой)
        } catch (IllegalStateException e) { // Если Mono еще не подписан или активен
            return e.getMessage() == null || !e.getMessage().contains("Timeout"); // Еще выполняется
// Вероятно, ошибка, считаем завершенным
        } catch (Exception e) {
            return true; // Любая другая ошибка, считаем завершенным
        }
    }


    public boolean isLoggedIn() {
        return loggedIn.get() && enuApiClient.getCookieValue() != null;
    }

}
