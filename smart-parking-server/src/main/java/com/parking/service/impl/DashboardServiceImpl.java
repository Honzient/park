package com.parking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.parking.domain.entity.ParkingRecord;
import com.parking.domain.entity.ParkingSpot;
import com.parking.domain.vo.dashboard.DashboardCardVO;
import com.parking.domain.vo.dashboard.DashboardRealtimeVO;
import com.parking.domain.vo.dashboard.DashboardRecentRecordVO;
import com.parking.domain.vo.dashboard.DashboardSpotVO;
import com.parking.domain.vo.dashboard.DashboardTrendPointVO;
import com.parking.mapper.ParkingRecordMapper;
import com.parking.mapper.ParkingSpotMapper;
import com.parking.service.DashboardService;
import com.parking.util.DateTimeUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DashboardServiceImpl implements DashboardService {

    private static final DateTimeFormatter HOUR_LABEL = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("MM-dd");
    private static final String RECORD_STATUS_EXITED = "\u5DF2\u51FA\u573A";

    private final ParkingRecordMapper parkingRecordMapper;
    private final ParkingSpotMapper parkingSpotMapper;

    public DashboardServiceImpl(ParkingRecordMapper parkingRecordMapper, ParkingSpotMapper parkingSpotMapper) {
        this.parkingRecordMapper = parkingRecordMapper;
        this.parkingSpotMapper = parkingSpotMapper;
    }

    @Override
    public DashboardRealtimeVO realtime(String range) {
        reconcileSpotRecordConsistency();
        DashboardCardVO cards = buildCards();
        List<DashboardSpotVO> spots = parkingSpotMapper.selectList(new QueryWrapper<ParkingSpot>().orderByAsc("spot_no"))
                .stream()
                .map(spot -> new DashboardSpotVO(
                        spot.getSpotNo(),
                        spot.getStatus(),
                        spot.getCurrentPlate(),
                        DateTimeUtils.format(spot.getEntryTime())
                ))
                .toList();

        Page<ParkingRecord> page = new Page<>(1, 10);
        Page<ParkingRecord> latest = parkingRecordMapper.selectPage(page, new QueryWrapper<ParkingRecord>().orderByDesc("entry_time"));
        List<DashboardRecentRecordVO> recentRecords = latest.getRecords().stream()
                .map(record -> new DashboardRecentRecordVO(
                        record.getPlateNumber(),
                        DateTimeUtils.format(record.getEntryTime()),
                        DateTimeUtils.format(record.getExitTime()),
                        record.getExitTime() == null ? "\u672A\u51FA\u573A" : "\u5DF2\u51FA\u573A"
                ))
                .toList();

        List<DashboardTrendPointVO> trend = buildTrend(range == null ? "TODAY" : range);
        return new DashboardRealtimeVO(cards, spots, recentRecords, trend);
    }

    private void reconcileSpotRecordConsistency() {
        List<ParkingSpot> occupiedSpots = parkingSpotMapper.selectList(new QueryWrapper<ParkingSpot>()
                .eq("status", "OCCUPIED")
                .orderByAsc("spot_no"));

        for (ParkingSpot spot : occupiedSpots) {
            ParkingRecord latestRecord = findLatestRecordByPlate(spot.getCurrentPlate());
            if (latestRecord == null || isExitedRecord(latestRecord)) {
                spot.setStatus("FREE");
                spot.setCurrentPlate(null);
                spot.setEntryTime(null);
                spot.setRecordId(null);
                parkingSpotMapper.updateById(spot);
            }
        }
    }

    private ParkingRecord findLatestRecordByPlate(String plateNumber) {
        if (!StringUtils.hasText(plateNumber)) {
            return null;
        }
        return parkingRecordMapper.selectList(new QueryWrapper<ParkingRecord>()
                        .eq("plate_number", plateNumber)
                        .orderByDesc("entry_time")
                        .last("limit 1"))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private boolean isExitedRecord(ParkingRecord record) {
        return RECORD_STATUS_EXITED.equals(record.getStatus()) || record.getExitTime() != null;
    }
    private DashboardCardVO buildCards() {
        long total = parkingSpotMapper.selectCount(new QueryWrapper<>());
        long occupied = parkingSpotMapper.selectCount(new QueryWrapper<ParkingSpot>().eq("status", "OCCUPIED"));
        long free = Math.max(total - occupied, 0);

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);

        BigDecimal todayIncome = sumIncome(todayStart, now);
        BigDecimal yesterdayIncome = sumIncome(yesterdayStart, todayStart.minusSeconds(1));

        double trendPercent = 0.0;
        if (yesterdayIncome.compareTo(BigDecimal.ZERO) > 0) {
            trendPercent = todayIncome.subtract(yesterdayIncome)
                    .divide(yesterdayIncome, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .doubleValue();
        } else if (todayIncome.compareTo(BigDecimal.ZERO) > 0) {
            trendPercent = 100.0;
        }

        return new DashboardCardVO(total, occupied, free, todayIncome, Math.round(trendPercent * 100.0) / 100.0);
    }

    private BigDecimal sumIncome(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<ParkingRecord> wrapper = new QueryWrapper<>();
        wrapper.select("IFNULL(SUM(fee), 0) AS income")
                .ge("exit_time", start)
                .le("exit_time", end);
        List<Map<String, Object>> maps = parkingRecordMapper.selectMaps(wrapper);
        if (maps.isEmpty()) {
            return BigDecimal.ZERO;
        }
        Object value = maps.get(0).get("income");
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString());
    }

    private List<DashboardTrendPointVO> buildTrend(String range) {
        return switch (range.toUpperCase()) {
            case "THIS_WEEK" -> weekTrend();
            case "THIS_MONTH" -> monthTrend();
            default -> todayTrend();
        };
    }

    private List<DashboardTrendPointVO> todayTrend() {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDateTime start = LocalDate.now().atStartOfDay();
        for (int hour = 0; hour < 24; hour += 2) {
            LocalDateTime slotStart = start.plusHours(hour);
            LocalDateTime slotEnd = slotStart.plusHours(2).minusSeconds(1);
            long traffic = parkingRecordMapper.selectCount(new LambdaQueryWrapper<ParkingRecord>()
                    .ge(ParkingRecord::getEntryTime, slotStart)
                    .le(ParkingRecord::getEntryTime, slotEnd));
            BigDecimal income = sumIncome(slotStart, slotEnd);
            points.add(new DashboardTrendPointVO(slotStart.format(HOUR_LABEL), traffic, income));
        }
        return points;
    }

    private List<DashboardTrendPointVO> weekTrend() {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int i = 0; i < 7; i++) {
            LocalDate day = monday.plusDays(i);
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.atTime(LocalTime.MAX);
            long traffic = parkingRecordMapper.selectCount(new LambdaQueryWrapper<ParkingRecord>()
                    .ge(ParkingRecord::getEntryTime, start)
                    .le(ParkingRecord::getEntryTime, end));
            BigDecimal income = sumIncome(start, end);
            points.add(new DashboardTrendPointVO(day.format(DAY_LABEL), traffic, income));
        }
        return points;
    }

    private List<DashboardTrendPointVO> monthTrend() {
        List<DashboardTrendPointVO> points = new ArrayList<>();
        LocalDate firstDay = LocalDate.now().withDayOfMonth(1);
        LocalDate now = LocalDate.now();
        for (LocalDate day = firstDay; !day.isAfter(now); day = day.plusDays(1)) {
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.atTime(LocalTime.MAX);
            long traffic = parkingRecordMapper.selectCount(new LambdaQueryWrapper<ParkingRecord>()
                    .ge(ParkingRecord::getEntryTime, start)
                    .le(ParkingRecord::getEntryTime, end));
            BigDecimal income = sumIncome(start, end);
            points.add(new DashboardTrendPointVO(day.format(DAY_LABEL), traffic, income));
        }
        return points;
    }
}

