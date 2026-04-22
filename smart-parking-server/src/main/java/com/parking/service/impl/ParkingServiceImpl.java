package com.parking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.common.PageResult;
import com.parking.common.exception.BusinessException;
import com.parking.domain.dto.parking.ParkingRecordQueryDTO;
import com.parking.domain.dto.parking.ParkingRecordUpdateDTO;
import com.parking.domain.dto.parking.SpotAssignDTO;
import com.parking.domain.dto.parking.SpotStatusUpdateDTO;
import com.parking.domain.entity.ParkingRecord;
import com.parking.domain.entity.ParkingSpot;
import com.parking.domain.enums.ParkingStatus;
import com.parking.domain.vo.parking.AssignmentVehicleVO;
import com.parking.domain.vo.parking.ParkingRecordVO;
import com.parking.domain.vo.parking.ParkingSpotVO;
import com.parking.mapper.ParkingRecordMapper;
import com.parking.mapper.ParkingSpotMapper;
import com.parking.security.SecurityUtils;
import com.parking.service.ParkingService;
import com.parking.util.DateTimeUtils;
import com.parking.util.PlateNumberUtils;
import com.parking.util.QueryValidator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ParkingServiceImpl implements ParkingService {

    private static final String CACHE_PREFIX = "cache:parking:";
    private static final String RECORD_STATUS_NOT_EXITED = "\u672A\u51FA\u573A";
    private static final String RECORD_STATUS_EXITED = "\u5DF2\u51FA\u573A";
    private static final BigDecimal FIXED_HOURLY_RATE = new BigDecimal("5.00");
    private static final BigDecimal DAILY_FEE_CAP = new BigDecimal("120.00");
    private static final int DAILY_CAP_HOURS = 24;

    private final ParkingRecordMapper parkingRecordMapper;
    private final ParkingSpotMapper parkingSpotMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public ParkingServiceImpl(ParkingRecordMapper parkingRecordMapper,
                              ParkingSpotMapper parkingSpotMapper,
                              StringRedisTemplate redisTemplate,
                              ObjectMapper objectMapper) {
        this.parkingRecordMapper = parkingRecordMapper;
        this.parkingSpotMapper = parkingSpotMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<ParkingRecordVO> queryRecords(ParkingRecordQueryDTO queryDTO) {
        QueryValidator.validateTimeRange(queryDTO.getStartTime(), queryDTO.getEndTime());

        String cacheKey = buildCacheKey(queryDTO);
        PageResult<ParkingRecordVO> cached = getCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        Page<ParkingRecord> page = new Page<>(queryDTO.getPageNo(), queryDTO.getPageSize());
        LambdaQueryWrapper<ParkingRecord> wrapper = Wrappers.lambdaQuery();

        if (StringUtils.hasText(queryDTO.getPlateNumber())) {
            wrapper.like(ParkingRecord::getPlateNumber, queryDTO.getPlateNumber());
        }
        if (StringUtils.hasText(queryDTO.getParkNo())) {
            wrapper.eq(ParkingRecord::getParkNo, queryDTO.getParkNo());
        }
        if (queryDTO.getStatuses() != null && !queryDTO.getStatuses().isEmpty()) {
            wrapper.in(ParkingRecord::getStatus, queryDTO.getStatuses());
        }
        if (queryDTO.getStartTime() != null) {
            wrapper.ge(ParkingRecord::getEntryTime, queryDTO.getStartTime());
        }
        if (queryDTO.getEndTime() != null) {
            wrapper.le(ParkingRecord::getEntryTime, queryDTO.getEndTime());
        }

        applySort(wrapper, queryDTO.getSortField(), queryDTO.getSortOrder());
        Page<ParkingRecord> resultPage = parkingRecordMapper.selectPage(page, wrapper);

        List<ParkingRecordVO> records = resultPage.getRecords().stream()
                .map(this::toVO)
                .toList();

        PageResult<ParkingRecordVO> result = new PageResult<>(
                records,
                resultPage.getTotal(),
                resultPage.getCurrent(),
                resultPage.getSize()
        );
        putCache(cacheKey, result);
        return result;
    }

    @Override
    public ParkingRecordVO getDetail(Long id) {
        ParkingRecord record = parkingRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(404, "Parking record not found");
        }
        return toVO(record);
    }


    @Override
    public void updateRecord(Long id, ParkingRecordUpdateDTO updateDTO) {
        ParkingRecord record = parkingRecordMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(404, "Parking record not found");
        }

        String originalPlateNumber = record.getPlateNumber();
        String originalParkNo = record.getParkNo();
        String targetRecordStatus = normalizeRecordStatus(updateDTO.getStatus());
        LocalDateTime entryTime = updateDTO.getEntryTime();
        LocalDateTime exitTime = updateDTO.getExitTime();
        boolean statusTransitionedToExited = isStatusTransitionedToExited(record.getStatus(), targetRecordStatus);

        if (RECORD_STATUS_EXITED.equals(targetRecordStatus)) {
            if (statusTransitionedToExited) {
                exitTime = LocalDateTime.now();
            } else if (exitTime == null) {
                exitTime = record.getExitTime() == null ? LocalDateTime.now() : record.getExitTime();
            }
        } else {
            exitTime = null;
        }

        if (exitTime != null && exitTime.isBefore(entryTime)) {
            throw new BusinessException(400, "Exit time must be later than entry time");
        }
        record.setPlateNumber(PlateNumberUtils.normalizeAndValidateRequired(updateDTO.getPlateNumber()));
        record.setParkNo(updateDTO.getParkNo().trim().toUpperCase());
        record.setEntryTime(entryTime);
        record.setExitTime(exitTime);
        Integer durationMinutes = resolveDurationMinutes(entryTime, exitTime);
        BigDecimal submittedFee = normalizeFee(updateDTO.getFee());
        if (RECORD_STATUS_EXITED.equals(targetRecordStatus)) {
            record.setFee(calculateFeeByRule(durationMinutes));
        } else {
            record.setFee(submittedFee);
        }
        record.setStatus(targetRecordStatus);
        record.setDurationMinutes(durationMinutes);

        parkingRecordMapper.updateById(record);
        if (statusTransitionedToExited) {
            releaseSpotForExitedRecord(record, originalPlateNumber, originalParkNo);
        }
        clearCache();
    }

    @Override
    public List<ParkingSpotVO> listSpots() {
        return parkingSpotMapper.selectList(new QueryWrapper<ParkingSpot>().orderByAsc("spot_no")).stream()
                .map(spot -> new ParkingSpotVO(
                        spot.getSpotNo(),
                        spot.getStatus(),
                        spot.getCurrentPlate(),
                        DateTimeUtils.format(spot.getEntryTime())
                ))
                .toList();
    }

    @Override
    public List<AssignmentVehicleVO> listAssignmentVehicles() {
        return parkingRecordMapper.selectList(new QueryWrapper<ParkingRecord>()
                        .isNull("exit_time")
                        .orderByDesc("entry_time"))
                .stream()
                .map(record -> new AssignmentVehicleVO(
                        record.getId(),
                        record.getPlateNumber(),
                        record.getParkNo(),
                        DateTimeUtils.format(record.getEntryTime())
                ))
                .toList();
    }

    @Override
    public void assignSpot(SpotAssignDTO assignDTO) {
        String plateNumber = PlateNumberUtils.normalizeAndValidateRequired(assignDTO.getPlateNumber());
        ParkingSpot targetSpot = findSpotByNo(assignDTO.getTargetSpotNo());
        if (targetSpot == null) {
            throw new BusinessException(404, "Target spot not found");
        }

        if ("OCCUPIED".equals(targetSpot.getStatus()) && !plateNumber.equals(targetSpot.getCurrentPlate())) {
            throw new BusinessException(400, "Target spot is already occupied");
        }

        ParkingSpot oldSpot = findSpotByCurrentPlate(plateNumber);
        ParkingRecord activeRecord = findActiveRecordByPlate(plateNumber);
        LocalDateTime now = LocalDateTime.now();

        if (oldSpot != null && !oldSpot.getSpotNo().equals(targetSpot.getSpotNo())) {
            oldSpot.setStatus("FREE");
            oldSpot.setCurrentPlate(null);
            oldSpot.setEntryTime(null);
            oldSpot.setRecordId(null);
            parkingSpotMapper.updateById(oldSpot);
        }

        if (activeRecord == null) {
            activeRecord = new ParkingRecord();
            activeRecord.setPlateNumber(plateNumber);
            activeRecord.setParkNo(targetSpot.getSpotNo());
            activeRecord.setEntryTime(now);
            activeRecord.setExitTime(null);
            activeRecord.setDurationMinutes(null);
            activeRecord.setFee(BigDecimal.ZERO);
            activeRecord.setStatus(RECORD_STATUS_NOT_EXITED);
            parkingRecordMapper.insert(activeRecord);
        } else {
            activeRecord.setParkNo(targetSpot.getSpotNo());
            activeRecord.setStatus(RECORD_STATUS_NOT_EXITED);
            parkingRecordMapper.updateById(activeRecord);
        }

        targetSpot.setStatus("OCCUPIED");
        targetSpot.setCurrentPlate(plateNumber);
        targetSpot.setEntryTime(activeRecord.getEntryTime() == null ? now : activeRecord.getEntryTime());
        targetSpot.setRecordId(activeRecord.getId());
        parkingSpotMapper.updateById(targetSpot);

        clearCache();
    }

    @Override
    public void updateSpotStatus(SpotStatusUpdateDTO updateDTO) {
        ParkingSpot spot = findSpotByNo(updateDTO.getSpotNo());
        if (spot == null) {
            throw new BusinessException(404, "Spot not found");
        }

        String currentStatus = normalizeParkingStatus(spot.getStatus());
        String targetStatus = normalizeParkingStatus(updateDTO.getTargetStatus());
        String nextPlate = PlateNumberUtils.normalizeAndValidateOptional(updateDTO.getPlateNumber());

        boolean requirePlate = ("FREE".equals(currentStatus) || "RESERVED".equals(currentStatus)) && "OCCUPIED".equals(targetStatus);
        if (requirePlate && !StringUtils.hasText(nextPlate)) {
            throw new BusinessException(400, PlateNumberUtils.invalidPlateMessage());
        }

        if ("OCCUPIED".equals(targetStatus)) {
            String effectivePlate = StringUtils.hasText(nextPlate) ? nextPlate : spot.getCurrentPlate();
            if (!StringUtils.hasText(effectivePlate)) {
                throw new BusinessException(400, PlateNumberUtils.invalidPlateMessage());
            }
            effectivePlate = PlateNumberUtils.normalizeAndValidateRequired(effectivePlate);

            LocalDateTime now = LocalDateTime.now();
            ParkingSpot oldSpot = findSpotByCurrentPlate(effectivePlate);
            if (oldSpot != null && !oldSpot.getSpotNo().equals(spot.getSpotNo())) {
                oldSpot.setStatus("FREE");
                oldSpot.setCurrentPlate(null);
                oldSpot.setEntryTime(null);
                oldSpot.setRecordId(null);
                parkingSpotMapper.updateById(oldSpot);
            }

            ParkingRecord activeRecord = findActiveRecordByPlate(effectivePlate);
            if (activeRecord == null) {
                activeRecord = new ParkingRecord();
                activeRecord.setPlateNumber(effectivePlate);
                activeRecord.setParkNo(spot.getSpotNo());
                activeRecord.setEntryTime(now);
                activeRecord.setExitTime(null);
                activeRecord.setDurationMinutes(null);
                activeRecord.setFee(BigDecimal.ZERO);
                activeRecord.setStatus(RECORD_STATUS_NOT_EXITED);
                parkingRecordMapper.insert(activeRecord);
            } else {
                activeRecord.setParkNo(spot.getSpotNo());
                activeRecord.setStatus(RECORD_STATUS_NOT_EXITED);
                parkingRecordMapper.updateById(activeRecord);
            }

            spot.setStatus("OCCUPIED");
            spot.setCurrentPlate(effectivePlate);
            if (requirePlate) {
                // FREE/RESERVED -> OCCUPIED: always append current system time.
                spot.setEntryTime(now);
            } else if (activeRecord.getEntryTime() != null) {
                spot.setEntryTime(activeRecord.getEntryTime());
            } else if (spot.getEntryTime() == null) {
                spot.setEntryTime(now);
            }
            spot.setRecordId(activeRecord.getId());
        } else {
            closeActiveRecordIfNeeded(spot, targetStatus);
            spot.setStatus(targetStatus);
            spot.setCurrentPlate(null);
            spot.setEntryTime(null);
            spot.setRecordId(null);
        }

        parkingSpotMapper.updateById(spot);
        clearCache();
    }

    private ParkingRecord findActiveRecordByPlate(String plateNumber) {
        if (!StringUtils.hasText(plateNumber)) {
            return null;
        }
        return parkingRecordMapper.selectList(new QueryWrapper<ParkingRecord>()
                        .eq("plate_number", plateNumber)
                        .isNull("exit_time")
                        .orderByDesc("entry_time")
                        .last("limit 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ParkingSpot findSpotByRecordId(Long recordId) {
        if (recordId == null) {
            return null;
        }
        return parkingSpotMapper.selectList(new QueryWrapper<ParkingSpot>()
                        .eq("record_id", recordId)
                        .orderByDesc("id")
                        .last("limit 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private void releaseSpotForExitedRecord(ParkingRecord record, String originalPlateNumber, String originalParkNo) {
        ParkingSpot spot = findSpotByRecordId(record.getId());
        if (spot == null && StringUtils.hasText(record.getPlateNumber())) {
            spot = findSpotByCurrentPlate(record.getPlateNumber());
        }
        if (spot == null && StringUtils.hasText(originalPlateNumber)) {
            spot = findSpotByCurrentPlate(originalPlateNumber);
        }
        if (spot == null && StringUtils.hasText(record.getParkNo())) {
            spot = findSpotByNo(record.getParkNo());
        }
        if (spot == null && StringUtils.hasText(originalParkNo)) {
            spot = findSpotByNo(originalParkNo);
        }
        if (spot == null || ParkingStatus.FREE.name().equals(spot.getStatus())) {
            return;
        }
        if (!isSpotAssociatedWithRecord(spot, record.getId(), record.getPlateNumber(), originalPlateNumber)) {
            return;
        }

        spot.setStatus(ParkingStatus.FREE.name());
        spot.setCurrentPlate(null);
        spot.setEntryTime(null);
        spot.setRecordId(null);
        parkingSpotMapper.updateById(spot);
    }

    private boolean isSpotAssociatedWithRecord(ParkingSpot spot, Long recordId, String... plateCandidates) {
        if (recordId != null && recordId.equals(spot.getRecordId())) {
            return true;
        }
        if (!StringUtils.hasText(spot.getCurrentPlate())) {
            return false;
        }
        for (String candidate : plateCandidates) {
            if (StringUtils.hasText(candidate) && candidate.equals(spot.getCurrentPlate())) {
                return true;
            }
        }
        return false;
    }

    private void closeActiveRecordIfNeeded(ParkingSpot spot, String targetStatus) {
        ParkingRecord activeRecord = null;

        if (spot.getRecordId() != null) {
            activeRecord = parkingRecordMapper.selectById(spot.getRecordId());
        }

        if (activeRecord == null && StringUtils.hasText(spot.getCurrentPlate())) {
            activeRecord = findActiveRecordByPlate(spot.getCurrentPlate());
        }

        if (activeRecord == null || activeRecord.getExitTime() != null) {
            return;
        }

        LocalDateTime exitTime = LocalDateTime.now();
        if (activeRecord.getEntryTime() == null && spot.getEntryTime() != null) {
            activeRecord.setEntryTime(spot.getEntryTime());
        }
        activeRecord.setExitTime(exitTime);
        Integer durationMinutes = resolveDurationMinutes(activeRecord.getEntryTime(), exitTime);
        activeRecord.setDurationMinutes(durationMinutes);
        if (isStatusTransitionedToExited(activeRecord.getStatus(), RECORD_STATUS_EXITED)) {
            activeRecord.setFee(calculateFeeByRule(durationMinutes));
        }
        activeRecord.setStatus(RECORD_STATUS_EXITED);
        parkingRecordMapper.updateById(activeRecord);
    }

    private Integer resolveDurationMinutes(LocalDateTime entryTime, LocalDateTime exitTime) {
        if (entryTime == null || exitTime == null) {
            return null;
        }
        return (int) Math.max(Duration.between(entryTime, exitTime).toMinutes(), 0);
    }

    private boolean isStatusTransitionedToExited(String currentStatus, String targetStatus) {
        return !RECORD_STATUS_EXITED.equals(currentStatus) && RECORD_STATUS_EXITED.equals(targetStatus);
    }

    private BigDecimal calculateFeeByRule(Integer durationMinutes) {
        int chargeableHours = resolveChargeableHours(durationMinutes);
        int cappedHours = Math.min(chargeableHours, DAILY_CAP_HOURS);
        BigDecimal fee = FIXED_HOURLY_RATE.multiply(BigDecimal.valueOf(cappedHours));
        if (fee.compareTo(DAILY_FEE_CAP) > 0) {
            fee = DAILY_FEE_CAP;
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private int resolveChargeableHours(Integer durationMinutes) {
        if (durationMinutes == null || durationMinutes <= 0) {
            return 1;
        }
        return (int) Math.ceil(durationMinutes / 60.0d);
    }
    private BigDecimal normalizeFee(BigDecimal fee) {
        if (fee == null || fee.compareTo(BigDecimal.ZERO) < 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    private ParkingSpot findSpotByNo(String spotNo) {
        if (!StringUtils.hasText(spotNo)) {
            return null;
        }
        return parkingSpotMapper.selectList(new QueryWrapper<ParkingSpot>()
                        .eq("spot_no", spotNo)
                        .orderByDesc("id")
                        .last("limit 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private ParkingSpot findSpotByCurrentPlate(String plateNumber) {
        if (!StringUtils.hasText(plateNumber)) {
            return null;
        }
        return parkingSpotMapper.selectList(new QueryWrapper<ParkingSpot>()
                        .eq("current_plate", plateNumber)
                        .orderByDesc("id")
                        .last("limit 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private String normalizeParkingStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new BusinessException(400, "Spot status is required");
        }
        try {
            return ParkingStatus.valueOf(status.trim().toUpperCase()).name();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "Invalid spot status: " + status);
        }
    }

    private String normalizeRecordStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new BusinessException(400, "Record status is required");
        }
        String normalized = status.trim();
        if (RECORD_STATUS_NOT_EXITED.equals(normalized) || RECORD_STATUS_EXITED.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(400, "Invalid record status: " + status);
    }

    private void clearCache() {
        try {
            redisTemplate.delete(redisTemplate.keys(CACHE_PREFIX + "*"));
        } catch (Exception ignored) {
        }
    }

    private ParkingRecordVO toVO(ParkingRecord record) {
        boolean notExited = record.getExitTime() == null;
        return new ParkingRecordVO(
                record.getId(),
                record.getPlateNumber(),
                record.getParkNo(),
                DateTimeUtils.format(record.getEntryTime()),
                DateTimeUtils.format(record.getExitTime()),
                DateTimeUtils.formatDuration(record.getEntryTime(), record.getExitTime()),
                record.getFee(),
                record.getStatus(),
                notExited
        );
    }

    private void applySort(LambdaQueryWrapper<ParkingRecord> wrapper, String sortField, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String normalizedField = normalize(sortField);

        switch (normalizedField) {
            case "platenumber" -> wrapper.orderBy(true, asc, ParkingRecord::getPlateNumber);
            case "parkno" -> wrapper.orderBy(true, asc, ParkingRecord::getParkNo);
            case "exittime" -> wrapper.orderBy(true, asc, ParkingRecord::getExitTime);
            case "durationminutes" -> wrapper.orderBy(true, asc, ParkingRecord::getDurationMinutes);
            case "fee" -> wrapper.orderBy(true, asc, ParkingRecord::getFee);
            case "status" -> wrapper.orderBy(true, asc, ParkingRecord::getStatus);
            case "entrytime" -> wrapper.orderBy(true, asc, ParkingRecord::getEntryTime);
            default -> wrapper.orderByDesc(ParkingRecord::getEntryTime);
        }
    }

    private String normalize(String field) {
        if (!StringUtils.hasText(field)) {
            return "entrytime";
        }
        return field.replace("_", "").toLowerCase();
    }

    private String buildCacheKey(ParkingRecordQueryDTO queryDTO) {
        String username = SecurityUtils.getCurrentUsername().orElse("anonymous");
        String queryJson;
        try {
            queryJson = objectMapper.writeValueAsString(queryDTO);
        } catch (JsonProcessingException exception) {
            queryJson = String.valueOf(queryDTO.hashCode());
        }
        String hash = DigestUtils.md5DigestAsHex(queryJson.getBytes(StandardCharsets.UTF_8));
        return CACHE_PREFIX + username + ":" + hash;
    }

    private PageResult<ParkingRecordVO> getCache(String cacheKey) {
        try {
            String cachedJson = redisTemplate.opsForValue().get(cacheKey);
            if (!StringUtils.hasText(cachedJson)) {
                return null;
            }
            return objectMapper.readValue(cachedJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return null;
        }
    }

    private void putCache(String cacheKey, PageResult<ParkingRecordVO> result) {
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), Duration.ofSeconds(30));
        } catch (Exception ignored) {
        }
    }
}
