package com.parking.domain.vo.admin;

public record LoginLogVO(
        Long id,
        String username,
        String loginTime,
        String ip,
        String device,
        String loginStatus,
        String message
) {
}
