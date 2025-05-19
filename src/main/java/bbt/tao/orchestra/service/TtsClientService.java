package bbt.tao.orchestra.service;

import bbt.tao.orchestra.dto.syntez.TtsRequestDto; // Замените на ваш пакет
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
public class TtsClientService {

    private static final Logger log = LoggerFactory.getLogger(TtsClientService.class);

    private final WebClient ttsWebClient;
    private final String defaultVoiceSamplePath;
    private final String defaultLanguage;

    public TtsClientService(
            @Qualifier("ttsWebClient") WebClient ttsWebClient,
            @Value("${tts.service.voice-sample-path}") String defaultVoiceSamplePath,
            @Value("${tts.service.language}") String defaultLanguage) {
        this.ttsWebClient = ttsWebClient;
        this.defaultVoiceSamplePath = defaultVoiceSamplePath;
        this.defaultLanguage = defaultLanguage;
    }

    /**
     * Отправляет текст в TTS сервис для синтеза.
     * @param textToSynthesize Текст для синтеза.
     * @return Mono с массивом байт WAV аудио.
     */
    public Mono<byte[]> synthesize(String textToSynthesize) {

        if (textToSynthesize == null || textToSynthesize.trim().isEmpty()) {
            log.warn("Skipping TTS request for empty or blank text.");
            return Mono.empty();
        }
        // Используем путь к голосу и язык из конфигурации
        TtsRequestDto requestDto = new TtsRequestDto(textToSynthesize, defaultVoiceSamplePath, defaultLanguage);
        log.debug("Sending request to TTS service: Text='{}...'", textToSynthesize.substring(0, Math.min(textToSynthesize.length(), 50)));

        return ttsWebClient.post()
                .uri("/synthesize")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestDto)
                .retrieve()
                .bodyToMono(byte[].class)
                .retryWhen(Retry.backoff(2, Duration.ofMillis(500)) // Повторить 2 раза с задержкой 500мс
                        .filter(throwable -> throwable instanceof Exception) // Повторять при любых ошибках сети/сервера
                        .doBeforeRetry(retrySignal -> log.warn("Retrying TTS request after error: {}", retrySignal.failure().getMessage()))
                )
                .doOnError(error -> log.error("Failed to synthesize speech after retries: {}", error.getMessage()))
                .onErrorResume(error -> {
                    log.error("Giving up TTS request for text: '{}...'", textToSynthesize.substring(0, Math.min(textToSynthesize.length(), 30)));
                    return Mono.empty();
                });
    }
}