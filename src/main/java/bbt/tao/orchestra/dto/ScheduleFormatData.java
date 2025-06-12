package bbt.tao.orchestra.dto;

import bbt.tao.orchestra.dto.enu.platonus.PlatonusApiResponse;

public record ScheduleFormatData(
        PlatonusApiResponse apiResponse,
        DateResolutionDetails resolutionResult
) {}
