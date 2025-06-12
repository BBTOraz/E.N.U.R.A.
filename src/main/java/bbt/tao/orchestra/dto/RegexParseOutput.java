package bbt.tao.orchestra.dto;

import java.time.LocalDate;
import java.util.Optional;

public record RegexParseOutput(
        Optional<LocalDate> specificDate,
        Optional<Integer> weekNumber,
        String matchedPhraseForLog
) {}