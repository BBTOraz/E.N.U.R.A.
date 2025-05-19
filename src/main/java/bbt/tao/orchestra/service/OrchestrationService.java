package bbt.tao.orchestra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private static final Pattern SENTENCE_END_PATTERN = Pattern.compile("(?<=[.?!…])\\s*");
    private static final long CHUNK_BUFFER_TIMEOUT_MS = 700;

    private final AIService llmService;
    private final TtsClientService ttsClientService;
    private final AudioPlaybackService audioPlaybackService;

    public OrchestrationService(AIService llmService,
                                TtsClientService ttsClientService,
                                AudioPlaybackService audioPlaybackService) {
        this.llmService = llmService;
        this.ttsClientService = ttsClientService;
        this.audioPlaybackService = audioPlaybackService;
    }

    /**
     * Основной метод, запускающий цикл STT -> LLM -> TTS -> Playback.
     * @param sttText Текст, полученный от STT.
     * @return Mono<Void>, завершающийся после обработки всего потока LLM.
     */
    public Mono<Void> orchestrate(String sttText) {
        log.info("Orchestration started for STT input: '{}'", sttText);

        return llmService.generateStreaming(sttText) // Получаем Flux<String> чанков LLM
                .publishOn(Schedulers.boundedElastic()) // Переключаем обработку на другой поток, чтобы не блокировать WS/Netty
                .doOnNext(llmChunk -> log.debug("Processing LLM chunk: '{}...'", llmChunk))
                .bufferTimeout(100, Duration.ofMillis(CHUNK_BUFFER_TIMEOUT_MS))
                .filter(list-> !list.isEmpty()) // Пропускаем пустые списки
                .map(list -> String.join("", list)) // Объединяем чанки в одно предложение
                .flatMap(this::splitAndProcessText)
                .then() // Возвращаем Mono<Void>, когда весь Flux обработан
                .doOnTerminate(() -> log.info("Orchestration finished for STT input: '{}'", sttText))
                .doOnError(e -> log.error("Orchestration failed for STT input: {}", sttText, e));
    }

    private Flux<Void> splitAndProcessText(String textBlock) {
        log.debug("Processing text block: '{}...'", textBlock.substring(0, Math.min(textBlock.length(), 50)));
        // Разбиваем блок текста по разделителям предложений (можно улучшить логику)
        // Используем limit = -1, чтобы сохранить пустые строки в конце, если они есть
        String[] segments = SENTENCE_END_PATTERN.split(textBlock, -1);

        // Убираем пустые строки, кроме, возможно, последней (если она результат split)
        List<String> nonEmptySegments = Flux.fromArray(segments)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collectList()
                .block(); // Собираем непустые сегменты (блокирующая операция, но на коротком списке)

        if (nonEmptySegments == null || nonEmptySegments.isEmpty()) {
            return Flux.empty(); // Нет сегментов для обработки
        }

        log.debug("Split into {} non-empty segments for TTS.", nonEmptySegments.size());

        // Обрабатываем каждый сегмент ПОСЛЕДОВАТЕЛЬНО с помощью concatMap
        return Flux.fromIterable(nonEmptySegments)
                .concatMap(segment -> {
                    log.info(">>> Sending to TTS: '{}'", segment); // Логируем ЧТО отправляем в TTS
                    return ttsClientService.synthesize(segment) // Вызываем TTS -> Mono<byte[]>
                            .flatMap(audioBytes -> {
                                if (audioBytes != null && audioBytes.length > 0) {
                                    log.debug("Received {} audio bytes for segment, submitting for playback.", audioBytes.length);
                                    return audioPlaybackService.playAudio(audioBytes); // Проигрываем -> Mono<Void>
                                } else {
                                    log.warn("Received empty audio from TTS for segment: '{}'", segment);
                                    return Mono.empty(); // Пропускаем, если аудио пустое
                                }
                            })
                            .doOnError(ttsError -> log.error("Error during TTS/Playback for segment: {}", segment, ttsError))
                            .onErrorResume(e -> { // Игнорируем ошибку сегмента, чтобы поток продолжился
                                log.error("Ignoring error for segment and continuing: {}", segment);
                                return Mono.empty();
                            });
                }); // concatMap гарантирует, что следующий сегмент не начнется, пока Mono<Void> от предыдущего не завершится
    }

}