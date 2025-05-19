package bbt.tao.orchestra.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class AudioPlaybackService {

    private static final Logger log = LoggerFactory.getLogger(AudioPlaybackService.class);

    private final String preferredMixerName;
    // Используем отдельный однопоточный Executor для последовательного воспроизведения
    // Чтобы гарантировать, что чанки играют один за другим без наложений
    private final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor();

    public AudioPlaybackService(@Value("${audio.playback.mixer-name:}") String preferredMixerName) {
        this.preferredMixerName = preferredMixerName;
        log.info("AudioPlaybackService initialized. Preferred Mixer: '{}'", preferredMixerName.isEmpty() ? "Default" : preferredMixerName);
        // Добавляем хук для корректного завершения Executor'а при остановке приложения
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down Playback ExecutorService...");
            playbackExecutor.shutdown();
            try {
                if (!playbackExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    playbackExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                playbackExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("Playback ExecutorService shut down.");
        }));
    }

    /**
     * Асинхронно проигрывает аудиоданные WAV.
     * Выполняет проигрывание в отдельном потоке для неблокирования.
     * Гарантирует последовательное проигрывание.
     * @param wavAudioBytes Массив байт с аудиоданными в формате WAV.
     * @return Mono<Void>, завершающийся после постановки задачи на проигрывание.
     */
    public Mono<Void> playAudio(byte[] wavAudioBytes) {
        if (wavAudioBytes == null || wavAudioBytes.length == 0) {
            log.warn("Received empty audio data, skipping playback.");
            return Mono.empty();
        }

        return Mono.fromRunnable(() -> playbackExecutor.submit(() -> {
                    log.debug("Submitting audio ({} bytes) for playback.", wavAudioBytes.length);
                    Mixer.Info mixerInfo = findMixerInfo();
                    AudioInputStream audioInputStream = null;
                    SourceDataLine line = null;

                    try {
                        audioInputStream = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavAudioBytes));
                        AudioFormat format = audioInputStream.getFormat();
                        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

                        if (mixerInfo != null) {
                            Mixer mixer = AudioSystem.getMixer(mixerInfo);
                            line = (SourceDataLine) mixer.getLine(info);
                            log.debug("Using mixer: {}", mixerInfo.getName());
                        } else {
                            line = (SourceDataLine) AudioSystem.getLine(info); // Используем дефолтный
                            log.debug("Using default audio output line.");
                        }

                        line.open(format);
                        line.start();

                        byte[] buffer = new byte[4096];
                        int bytesRead;

                        while ((bytesRead = audioInputStream.read(buffer, 0, buffer.length)) != -1) {
                            line.write(buffer, 0, bytesRead);
                        }

                        line.drain();
                        log.debug("Audio playback finished.");

                    } catch (UnsupportedAudioFileException e) {
                        log.error("Unsupported audio file format during playback", e);
                    } catch (IOException e) {
                        log.error("IOException during audio playback", e);
                    } catch (LineUnavailableException e) {
                        log.error("Audio line unavailable. Mixer: {}", (mixerInfo != null ? mixerInfo.getName() : "Default"), e);
                    } catch (Exception e) {
                        log.error("Unexpected error during audio playback", e);
                    } finally {
                        if (line != null) {
                            line.close();
                        }
                        if (audioInputStream != null) {
                            try {
                                audioInputStream.close();
                            } catch (IOException e) {
                                log.error("Error closing audio input stream", e);
                            }
                        }
                    }
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /**
     * Ищет информацию о микшере по имени или возвращает null, если не найден или имя не задано.
     */
    private Mixer.Info findMixerInfo() {
        if (preferredMixerName == null || preferredMixerName.trim().isEmpty()) {
            log.warn("Preferred mixer name is not set, using default output line.");
            return null;
        }
        Optional<Mixer.Info> foundMixer = Arrays.stream(AudioSystem.getMixerInfo())
                .filter(info -> {
                    Mixer mixer = AudioSystem.getMixer(info);
                    Line.Info lineInfo = new Line.Info(SourceDataLine.class);
                    return info.getName().contains(preferredMixerName) && mixer.isLineSupported(lineInfo);
                })
                .findFirst();

        if (foundMixer.isPresent()) {
            return foundMixer.get();
        } else {
            log.warn("Mixer with name containing '{}' not found or doesn't support SourceDataLine. Using default.", preferredMixerName);
            return null;
        }
    }
}
