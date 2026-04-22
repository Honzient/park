package com.parking.domain.vo.datacenter;

public record DataCenterTimelineItemVO(
        String label,
        long entryCount,
        long exitCount
) {
}
