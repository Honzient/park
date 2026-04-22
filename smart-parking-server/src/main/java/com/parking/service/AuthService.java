package com.parking.service;

import com.parking.domain.dto.auth.LoginRequest;
import com.parking.domain.vo.auth.CaptchaVO;
import com.parking.domain.vo.auth.LoginVO;

public interface AuthService {

    CaptchaVO generateCaptcha();

    LoginVO login(LoginRequest request);
}
