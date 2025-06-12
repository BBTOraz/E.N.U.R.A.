package bbt.tao.orchestra.dto;

import bbt.tao.orchestra.dto.enu.platonus.PlatonusScheduleRequest;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;

public record DateResolutionDetails(
        Optional<LocalDate> userRequestedDate,
        Optional<DayOfWeek> dayOfWeekToFilter,
        PlatonusScheduleRequest apiRequestParams,
        ResolvedQueryType queryType,
        String userFriendlySummary
) {
}
