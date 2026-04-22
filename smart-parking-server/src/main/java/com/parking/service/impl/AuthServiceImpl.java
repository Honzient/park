package com.parking.service.impl;

import com.google.code.kaptcha.impl.DefaultKaptcha;
import com.parking.common.exception.BusinessException;
import com.parking.config.CaptchaProperties;
import com.parking.domain.dto.auth.LoginRequest;
import com.parking.domain.vo.auth.CaptchaVO;
import com.parking.domain.vo.auth.LoginVO;
import com.parking.repository.InMemoryIdentityStore;
import com.parking.security.JwtTokenProvider;
import com.parking.service.AuthService;
import com.parking.service.OperationLogService;
import com.parking.util.RequestInfoUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthServiceImpl implements AuthService {

    private static final String CAPTCHA_PREFIX = "captcha:";
    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";
    private static final int MAX_FAIL = 3;

    private final DefaultKaptcha kaptcha;
    private final StringRedisTemplate redisTemplate;
    private final CaptchaProperties captchaProperties;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final InMemoryIdentityStore identityStore;
    private final OperationLogService operationLogService;

    // Local fallback cache when Redis is unavailable.
    private final Map<String, LocalCacheValue<String>> localCaptchaCache = new ConcurrentHashMap<>();
    private final Map<String, LocalCacheValue<Integer>> localFailCache = new ConcurrentHashMap<>();
    private final Map<String, LocalCacheValue<String>> localLockCache = new ConcurrentHashMap<>();

    public AuthServiceImpl(DefaultKaptcha kaptcha,
                           StringRedisTemplate redisTemplate,
                           CaptchaProperties captchaProperties,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider,
                           InMemoryIdentityStore identityStore,
                           OperationLogService operationLogService) {
        this.kaptcha = kaptcha;
        this.redisTemplate = redisTemplate;
        this.captchaProperties = captchaProperties;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.identityStore = identityStore;
        this.operationLogService = operationLogService;
    }

    @Override
    public CaptchaVO generateCaptcha() {
        String text = kaptcha.createText();
        BufferedImage image = kaptcha.createImage(text);

        String captchaId = UUID.randomUUID().toString().replace("-", "");
        cacheCaptcha(captchaId, text.toLowerCase(Locale.ROOT));
        return new CaptchaVO(captchaId, "data:image/png;base64," + encodeImage(image));
    }

    @Override
    public LoginVO login(LoginRequest request) {
        HttpServletRequest httpRequest = currentRequest();
        String ip = RequestInfoUtils.clientIp(httpRequest);
        String device = RequestInfoUtils.device(httpRequest);

        if (isLocked(request.getUsername())) {
            operationLogService.logLogin(request.getUsername(), "FAIL", "Account locked for 30 minutes", ip, device, LocalDateTime.now());
            throw new BusinessException(423, "Login failed 3 times. Account locked for 30 minutes");
        }

        String cachedCode = consumeCaptcha(CAPTCHA_PREFIX + request.getCaptchaId());
        String requestCaptcha = request.getCaptchaCode() == null
                ? null
                : request.getCaptchaCode().trim().toLowerCase(Locale.ROOT);
        if (cachedCode == null || !Objects.equals(cachedCode, requestCaptcha)) {
            operationLogService.logLogin(request.getUsername(), "FAIL", "Captcha invalid", ip, device, LocalDateTime.now());
            throw new BusinessException(400, "Captcha is invalid or expired");
        }

        InMemoryIdentityStore.UserAccount user = identityStore.getByUsername(request.getUsername());
        if (user == null || !isPasswordValid(user, request.getPassword())) {
            int failCount = increaseFailCount(request.getUsername());
            if (failCount >= MAX_FAIL) {
                lockAccount(request.getUsername());
                operationLogService.logLogin(request.getUsername(), "FAIL", "Account locked for 30 minutes", ip, device, LocalDateTime.now());
                throw new BusinessException(423, "Login failed 3 times. Account locked for 30 minutes");
            }
            operationLogService.logLogin(request.getUsername(), "FAIL", "Username or password invalid", ip, device, LocalDateTime.now());
            throw new BusinessException(401, "Username or password is invalid");
        }

        if (!"ENABLED".equalsIgnoreCase(user.status())) {
            operationLogService.logLogin(request.getUsername(), "FAIL", "Account disabled", ip, device, LocalDateTime.now());
            throw new BusinessException(403, "Account is disabled");
        }

        resetFailCount(request.getUsername());
        identityStore.updateLastLogin(user.username(), LocalDateTime.now());

        var permissions = identityStore.getPermissions(user.roleCode());
        String token = jwtTokenProvider.createToken(user.username(), permissions);
        long expireAt = jwtTokenProvider.getExpireAt(token);

        operationLogService.logLogin(request.getUsername(), "SUCCESS", "Login success", ip, device, LocalDateTime.now());
        return new LoginVO(token, "Bearer", expireAt, user.username(), permissions);
    }

    private boolean isPasswordValid(InMemoryIdentityStore.UserAccount user, String rawPassword) {
        if (user == null || !StringUtils.hasText(user.passwordHash())) {
            return false;
        }

        try {
            if (passwordEncoder.matches(rawPassword, user.passwordHash())) {
                return true;
            }
        } catch (Exception ignored) {
            // Continue to legacy plaintext compatibility check.
        }

        if (Objects.equals(rawPassword, user.passwordHash())) {
            // Upgrade legacy plaintext password to BCrypt hash after successful login.
            identityStore.updatePassword(user.username(), passwordEncoder.encode(rawPassword));
            return true;
        }

        return false;
    }

    private HttpServletRequest currentRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes == null ? null : attributes.getRequest();
    }

    private boolean isLocked(String username) {
        String lockKey = LOCK_PREFIX + username;
        try {
            String value = redisTemplate.opsForValue().get(lockKey);
            return StringUtils.hasText(value);
        } catch (Exception ignored) {
            return getLocalValue(localLockCache, lockKey) != null;
        }
    }

    private int increaseFailCount(String username) {
        String failKey = FAIL_PREFIX + username;
        Duration duration = Duration.ofMinutes(30);
        try {
            Long count = redisTemplate.opsForValue().increment(failKey);
            redisTemplate.expire(failKey, duration);
            return count == null ? 0 : count.intValue();
        } catch (Exception ignored) {
            Integer current = getLocalValue(localFailCache, failKey);
            int next = (current == null ? 0 : current) + 1;
            putLocalValue(localFailCache, failKey, next, duration);
            return next;
        }
    }

    private void resetFailCount(String username) {
        String failKey = FAIL_PREFIX + username;
        try {
            redisTemplate.delete(failKey);
        } catch (Exception ignored) {
        }
        localFailCache.remove(failKey);
    }

    private void lockAccount(String username) {
        String lockKey = LOCK_PREFIX + username;
        String failKey = FAIL_PREFIX + username;
        Duration duration = Duration.ofMinutes(30);
        try {
            redisTemplate.opsForValue().set(lockKey, "1", duration);
            redisTemplate.delete(failKey);
        } catch (Exception ignored) {
        }
        putLocalValue(localLockCache, lockKey, "1", duration);
        localFailCache.remove(failKey);
    }

    private void cacheCaptcha(String captchaId, String captchaCode) {
        Duration expireDuration = Duration.ofMinutes(captchaProperties.getExpireMinutes());
        String cacheKey = CAPTCHA_PREFIX + captchaId;
        try {
            redisTemplate.opsForValue().set(cacheKey, captchaCode, expireDuration);
        } catch (Exception ignored) {
            putLocalValue(localCaptchaCache, cacheKey, captchaCode, expireDuration);
        }
    }

    private String consumeCaptcha(String captchaCacheKey) {
        try {
            String cachedCode = redisTemplate.opsForValue().get(captchaCacheKey);
            if (cachedCode != null) {
                redisTemplate.delete(captchaCacheKey);
            }
            return cachedCode;
        } catch (Exception ignored) {
            String localCode = getLocalValue(localCaptchaCache, captchaCacheKey);
            localCaptchaCache.remove(captchaCacheKey);
            return localCode;
        }
    }

    private String encodeImage(BufferedImage image) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", outputStream);
            return java.util.Base64.getEncoder().encodeToString(outputStream.toByteArray());
        } catch (IOException exception) {
            throw new BusinessException(500, "Failed to generate captcha");
        }
    }

    private <T> void putLocalValue(Map<String, LocalCacheValue<T>> cache, String key, T value, Duration ttl) {
        cache.put(key, new LocalCacheValue<>(value, Instant.now().plus(ttl)));
    }

    private <T> T getLocalValue(Map<String, LocalCacheValue<T>> cache, String key) {
        LocalCacheValue<T> local = cache.get(key);
        if (local == null) {
            return null;
        }
        if (local.expireAt().isBefore(Instant.now())) {
            cache.remove(key);
            return null;
        }
        return local.value();
    }

    private record LocalCacheValue<T>(T value, Instant expireAt) {
    }
}
