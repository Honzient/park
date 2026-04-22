package com.parking.common;

import java.util.List;

public record PageResult<T>(List<T> records, long total, long pageNo, long pageSize) {
}
