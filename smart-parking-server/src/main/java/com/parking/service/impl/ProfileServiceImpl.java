package com.parking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.parking.common.PageResult;
import com.parking.common.exception.BusinessException;
import com.parking.domain.dto.profile.PasswordChangeDTO;
import com.parking.domain.dto.profile.ProfileUpdateDTO;
import com.parking.domain.entity.LoginLog;
import com.parking.domain.vo.admin.LoginLogVO;
import com.parking.domain.vo.profile.ProfileVO;
import com.parking.mapper.LoginLogMapper;
import com.parking.repository.InMemoryIdentityStore;
import com.parking.service.OperationLogService;
import com.parking.service.ProfileService;
import com.parking.util.DateTimeUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

@Service
public class ProfileServiceImpl implements ProfileService {

    private final InMemoryIdentityStore identityStore;
    private final PasswordEncoder passwordEncoder;
    private final OperationLogService operationLogService;
    private final LoginLogMapper loginLogMapper;

    public ProfileServiceImpl(InMemoryIdentityStore identityStore,
                              PasswordEncoder passwordEncoder,
                              OperationLogService operationLogService,
                              LoginLogMapper loginLogMapper) {
        this.identityStore = identityStore;
        this.passwordEncoder = passwordEncoder;
        this.operationLogService = operationLogService;
        this.loginLogMapper = loginLogMapper;
    }

    @Override
    public ProfileVO profile(String username) {
        InMemoryIdentityStore.UserAccount user = identityStore.getByUsername(username);
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }
        return new ProfileVO(
                user.username(),
                user.realName(),
                user.phone(),
                user.roleCode(),
                DateTimeUtils.format(user.lastLoginTime())
        );
    }

    @Override
    public void updateProfile(String username, ProfileUpdateDTO dto, String requestUri, String ip, String device) {
        String targetUsername = dto.getUsername().trim();
        identityStore.updateProfile(username, targetUsername, dto.getRealName(), dto.getPhone());
        operationLogService.log(username, "Update profile", requestUri, ip, device);
    }

    @Override
    public void changePassword(String username, PasswordChangeDTO dto, String requestUri, String ip, String device) {
        InMemoryIdentityStore.UserAccount user = identityStore.getByUsername(username);
        if (user == null) {
            throw new BusinessException(404, "User not found");
        }

        if (!matchesPassword(dto.getOldPassword(), user.passwordHash())) {
            throw new BusinessException(400, "Old password is incorrect");
        }

        identityStore.updatePassword(username, passwordEncoder.encode(dto.getNewPassword()));
        operationLogService.log(username, "Change password", requestUri, ip, device);
    }

    @Override
    public PageResult<LoginLogVO> loginLogs(String username, long pageNo, long pageSize) {
        Page<LoginLog> page = new Page<>(pageNo, pageSize);
        QueryWrapper<LoginLog> wrapper = new QueryWrapper<>();
        wrapper.eq("username", username).orderByDesc("login_time");
        Page<LoginLog> result = loginLogMapper.selectPage(page, wrapper);

        List<LoginLogVO> records = result.getRecords().stream().map(log -> new LoginLogVO(
                log.getId(),
                log.getUsername(),
                DateTimeUtils.format(log.getLoginTime()),
                log.getIp(),
                log.getDevice(),
                log.getLoginStatus(),
                log.getMessage()
        )).toList();

        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    private boolean matchesPassword(String rawPassword, String encodedPassword) {
        if (!StringUtils.hasText(encodedPassword)) {
            return false;
        }

        try {
            if (passwordEncoder.matches(rawPassword, encodedPassword)) {
                return true;
            }
        } catch (Exception ignored) {
            // Continue to legacy plaintext compatibility check.
        }

        return Objects.equals(rawPassword, encodedPassword);
    }
}
