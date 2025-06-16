package bbt.tao.orchestra.dto;

import bbt.tao.orchestra.dto.enu.platonus.schedule.PlatonusApiResponse;

public record ScheduleFormatData(
        PlatonusApiResponse apiResponse,
        DateResolutionDetails resolutionResult
) {}
