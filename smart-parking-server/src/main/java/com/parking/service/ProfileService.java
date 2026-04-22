package com.parking.service;

import com.parking.common.PageResult;
import com.parking.domain.dto.profile.PasswordChangeDTO;
import com.parking.domain.dto.profile.ProfileUpdateDTO;
import com.parking.domain.vo.admin.LoginLogVO;
import com.parking.domain.vo.profile.ProfileVO;

public interface ProfileService {

    ProfileVO profile(String username);

    void updateProfile(String username, ProfileUpdateDTO dto, String requestUri, String ip, String device);

    void changePassword(String username, PasswordChangeDTO dto, String requestUri, String ip, String device);

    PageResult<LoginLogVO> loginLogs(String username, long pageNo, long pageSize);
}
