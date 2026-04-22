package com.parking.domain.vo.auth;

import java.util.List;

public record LoginVO(String token, String tokenType, long expireAt, String username, List<String> permissions) {
}
