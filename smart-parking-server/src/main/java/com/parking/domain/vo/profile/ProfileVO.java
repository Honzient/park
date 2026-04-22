package com.parking.domain.vo.profile;

public record ProfileVO(
        String username,
        String realName,
        String phone,
        String roleCode,
        String lastLoginTime
) {
}
