package com.parking.util;

import com.parking.common.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

public final class PlateNumberUtils {

    private static final String INVALID_PLATE_MESSAGE = "\u8f66\u724c\u53f7\u683c\u5f0f\u4e0d\u7b26";

    // Equivalent to:
    // /^[京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼使领][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9挂学警港澳]$/
    private static final Pattern CHINA_PLATE_PATTERN = Pattern.compile(
            "^[\u4eac\u6d25\u6caa\u6e1d\u5180\u8c6b\u4e91\u8fbd\u9ed1\u6e58\u7696\u9c81\u65b0\u82cf\u6d59\u8d63\u9102\u6842\u7518\u664b\u8499\u9655\u5409\u95fd\u8d35\u7ca4\u9752\u85cf\u5ddd\u5b81\u743c\u4f7f\u9886][A-HJ-NP-Z][A-HJ-NP-Z0-9]{4,5}[A-HJ-NP-Z0-9\u6302\u5b66\u8b66\u6e2f\u6fb3]$"
    );

    private PlateNumberUtils() {
    }

    public static String normalizeAndValidateRequired(String plateNumber) {
        if (!StringUtils.hasText(plateNumber)) {
            throw new BusinessException(400, INVALID_PLATE_MESSAGE);
        }
        return normalizeAndValidate(plateNumber);
    }

    public static String normalizeAndValidateOptional(String plateNumber) {
        if (!StringUtils.hasText(plateNumber)) {
            return null;
        }
        return normalizeAndValidate(plateNumber);
    }

    public static String invalidPlateMessage() {
        return INVALID_PLATE_MESSAGE;
    }

    private static String normalizeAndValidate(String plateNumber) {
        String normalized = plateNumber.trim().toUpperCase().replaceAll("\\s+", "");
        if (!CHINA_PLATE_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(400, INVALID_PLATE_MESSAGE);
        }
        return normalized;
    }
}
