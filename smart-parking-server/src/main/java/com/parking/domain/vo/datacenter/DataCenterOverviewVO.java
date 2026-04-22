package com.parking.domain.vo.datacenter;

import java.util.List;

public record DataCenterOverviewVO(
        DataCenterSummaryVO summary,
        List<DataCenterTimelineItemVO> timeline,
        List<DataCenterProvinceItemVO> provinceTop5,
        long recordCount
) {
}
