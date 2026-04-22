package com.parking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.parking.common.PageResult;
import com.parking.common.exception.BusinessException;
import com.parking.domain.dto.datacenter.DataCenterRecordQueryDTO;
import com.parking.domain.dto.parking.ParkingRecordQueryDTO;
import com.parking.domain.entity.ParkingRecord;
import com.parking.domain.vo.datacenter.DataCenterOverviewVO;
import com.parking.domain.vo.datacenter.DataCenterProvinceItemVO;
import com.parking.domain.vo.datacenter.DataCenterSummaryVO;
import com.parking.domain.vo.datacenter.DataCenterTimelineItemVO;
import com.parking.domain.vo.parking.ParkingRecordVO;
import com.parking.mapper.ParkingRecordMapper;
import com.parking.service.DataCenterService;
import com.parking.service.ParkingService;
import com.parking.util.DateTimeUtils;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class DataCenterServiceImpl implements DataCenterService {

    private static final DateTimeFormatter TIMELINE_DAY_LABEL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String RECORD_STATUS_NOT_EXITED = "\u672a\u51fa\u573a";
    private static final String RECORD_STATUS_EXITED = "\u5df2\u51fa\u573a";
    private static final String UNKNOWN_PROVINCE = "\u672a\u77e5";

    private final ParkingRecordMapper parkingRecordMapper;
    private final ParkingService parkingService;

    public DataCenterServiceImpl(ParkingRecordMapper parkingRecordMapper, ParkingService parkingService) {
        this.parkingRecordMapper = parkingRecordMapper;
        this.parkingService = parkingService;
    }

    @Override
    public PageResult<ParkingRecordVO> queryRecords(DataCenterRecordQueryDTO queryDTO) {
        ParkingRecordQueryDTO parkingQuery = toParkingQuery(queryDTO);
        return parkingService.queryRecords(parkingQuery);
    }

    @Override
    public byte[] exportExcel(DataCenterRecordQueryDTO queryDTO) {
        List<ParkingRecordVO> records = loadExportRecords(queryDTO);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("\u505c\u8f66\u8bb0\u5f55");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("\u8f66\u724c\u53f7");
            header.createCell(1).setCellValue("\u8f66\u4f4d\u53f7");
            header.createCell(2).setCellValue("\u5165\u573a\u65f6\u95f4");
            header.createCell(3).setCellValue("\u51fa\u573a\u65f6\u95f4");
            header.createCell(4).setCellValue("\u505c\u8f66\u65f6\u957f");
            header.createCell(5).setCellValue("\u8d39\u7528(\u5143)");
            header.createCell(6).setCellValue("\u72b6\u6001");

            int rowIndex = 1;
            for (ParkingRecordVO record : records) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(record.plateNumber());
                row.createCell(1).setCellValue(record.parkNo());
                row.createCell(2).setCellValue(record.entryTime());
                row.createCell(3).setCellValue(record.exitTime() == null ? "\u672a\u51fa\u573a" : record.exitTime());
                row.createCell(4).setCellValue(record.duration());
                row.createCell(5).setCellValue(record.fee() == null ? 0D : record.fee().doubleValue());
                row.createCell(6).setCellValue(toStatusLabel(record.status()));
            }

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new RuntimeException("Failed to export datacenter excel", exception);
        }
    }

    @Override
    public byte[] exportPdf(DataCenterRecordQueryDTO queryDTO) {
        List<ParkingRecordVO> records = loadExportRecords(queryDTO);
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate());
            PdfWriter.getInstance(document, outputStream);
            document.open();

            BaseFont baseFont = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font titleFont = new Font(baseFont, 14, Font.BOLD);
            Font cellFont = new Font(baseFont, 10, Font.NORMAL);

            document.add(new Paragraph("\u505c\u8f66\u6570\u636e\u4e2d\u5fc3\u62a5\u8868", titleFont));
            document.add(new Paragraph(" ", cellFont));

            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.addCell(new Phrase("\u8f66\u724c\u53f7", cellFont));
            table.addCell(new Phrase("\u8f66\u4f4d\u53f7", cellFont));
            table.addCell(new Phrase("\u5165\u573a\u65f6\u95f4", cellFont));
            table.addCell(new Phrase("\u51fa\u573a\u65f6\u95f4", cellFont));
            table.addCell(new Phrase("\u505c\u8f66\u65f6\u957f", cellFont));
            table.addCell(new Phrase("\u8d39\u7528(\u5143)", cellFont));
            table.addCell(new Phrase("\u72b6\u6001", cellFont));

            for (ParkingRecordVO record : records) {
                table.addCell(new Phrase(record.plateNumber(), cellFont));
                table.addCell(new Phrase(record.parkNo(), cellFont));
                table.addCell(new Phrase(record.entryTime(), cellFont));
                table.addCell(new Phrase(record.exitTime() == null ? "\u672a\u51fa\u573a" : record.exitTime(), cellFont));
                table.addCell(new Phrase(record.duration(), cellFont));
                table.addCell(new Phrase(record.fee() == null ? "0.00" : record.fee().toString(), cellFont));
                table.addCell(new Phrase(toStatusLabel(record.status()), cellFont));
            }

            document.add(table);
            document.close();
            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new RuntimeException("\u5bfc\u51fa\u6570\u636e\u4e2d\u5fc3 PDF \u5931\u8d25", exception);
        }
    }

    @Override
    public DataCenterOverviewVO overview(DataCenterRecordQueryDTO queryDTO) {
        List<ParkingRecord> filteredRecords = loadFilteredRecords(queryDTO);
        QueryRange range = resolveQueryRange(queryDTO);
        List<DataCenterTimelineItemVO> timeline = buildTimeline(queryDTO, range.startTime(), range.endTime());
        List<DataCenterProvinceItemVO> provinceTop5 = buildProvinceTop5(filteredRecords);
        DataCenterSummaryVO summary = buildSummary(filteredRecords, timeline);

        return new DataCenterOverviewVO(summary, timeline, provinceTop5, filteredRecords.size());
    }

    private List<DataCenterTimelineItemVO> buildTimeline(DataCenterRecordQueryDTO queryDTO,
                                                         LocalDateTime startTime,
                                                         LocalDateTime endTime) {
        if (startTime == null || endTime == null) {
            return List.of();
        }

        LinkedHashMap<String, TimelineCounter> timelineBuckets = initTimelineBuckets(startTime, endTime);
        List<ParkingRecord> timelineRecords = loadTimelineRecords(queryDTO, startTime, endTime);

        for (ParkingRecord record : timelineRecords) {
            incrementTimelineCounter(timelineBuckets, record.getEntryTime(), startTime, endTime, true);
            incrementTimelineCounter(timelineBuckets, record.getExitTime(), startTime, endTime, false);
        }

        return timelineBuckets.entrySet().stream()
                .map(entry -> new DataCenterTimelineItemVO(
                        entry.getKey(),
                        entry.getValue().entryCount,
                        entry.getValue().exitCount
                ))
                .toList();
    }

    private List<ParkingRecord> loadTimelineRecords(DataCenterRecordQueryDTO queryDTO,
                                                    LocalDateTime startTime,
                                                    LocalDateTime endTime) {
        LambdaQueryWrapper<ParkingRecord> wrapper = buildBaseRecordWrapper(queryDTO);

        wrapper.and(condition -> condition
                .ge(ParkingRecord::getEntryTime, startTime)
                .le(ParkingRecord::getEntryTime, endTime)
                .or(exitCondition -> exitCondition
                        .ge(ParkingRecord::getExitTime, startTime)
                        .le(ParkingRecord::getExitTime, endTime)));
        wrapper.orderByAsc(ParkingRecord::getEntryTime);

        return parkingRecordMapper.selectList(wrapper);
    }

    private List<ParkingRecord> loadFilteredRecords(DataCenterRecordQueryDTO queryDTO) {
        QueryRange range = resolveQueryRange(queryDTO);
        LambdaQueryWrapper<ParkingRecord> wrapper = buildBaseRecordWrapper(queryDTO);

        if (range.startTime() != null) {
            wrapper.ge(ParkingRecord::getEntryTime, range.startTime());
        }
        if (range.endTime() != null) {
            wrapper.le(ParkingRecord::getEntryTime, range.endTime());
        }
        applySort(wrapper, queryDTO.getSortField(), queryDTO.getSortOrder());

        return parkingRecordMapper.selectList(wrapper);
    }

    private LambdaQueryWrapper<ParkingRecord> buildBaseRecordWrapper(DataCenterRecordQueryDTO queryDTO) {
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
        return wrapper;
    }

    private LinkedHashMap<String, TimelineCounter> initTimelineBuckets(LocalDateTime startTime,
                                                                       LocalDateTime endTime) {
        LinkedHashMap<String, TimelineCounter> buckets = new LinkedHashMap<>();
        LocalDateTime cursor = alignTimelineTime(startTime);
        LocalDateTime last = alignTimelineTime(endTime);

        while (!cursor.isAfter(last)) {
            buckets.put(formatTimelineLabel(cursor), new TimelineCounter());
            cursor = cursor.plusDays(1);
        }
        return buckets;
    }

    private void incrementTimelineCounter(Map<String, TimelineCounter> buckets,
                                          LocalDateTime eventTime,
                                          LocalDateTime startTime,
                                          LocalDateTime endTime,
                                          boolean entryEvent) {
        if (eventTime == null || eventTime.isBefore(startTime) || eventTime.isAfter(endTime)) {
            return;
        }

        String label = formatTimelineLabel(alignTimelineTime(eventTime));
        TimelineCounter counter = buckets.get(label);
        if (counter == null) {
            return;
        }

        if (entryEvent) {
            counter.entryCount++;
            return;
        }
        counter.exitCount++;
    }

    private LocalDateTime alignTimelineTime(LocalDateTime dateTime) {
        return dateTime.toLocalDate().atStartOfDay();
    }

    private String formatTimelineLabel(LocalDateTime dateTime) {
        return dateTime.format(TIMELINE_DAY_LABEL);
    }

    private List<DataCenterProvinceItemVO> buildProvinceTop5(List<ParkingRecord> records) {
        Map<String, Long> provinceCounter = new HashMap<>();
        for (ParkingRecord record : records) {
            provinceCounter.merge(extractProvince(record.getPlateNumber()), 1L, Long::sum);
        }

        return provinceCounter.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(5)
                .map(entry -> new DataCenterProvinceItemVO(entry.getKey(), entry.getValue()))
                .toList();
    }

    private DataCenterSummaryVO buildSummary(List<ParkingRecord> filteredRecords,
                                             List<DataCenterTimelineItemVO> timeline) {
        long recordCount = filteredRecords.size();
        long activeRecordCount = filteredRecords.stream()
                .filter(record -> record.getExitTime() == null)
                .count();
        long exitedRecordCount = recordCount - activeRecordCount;
        long entryEventCount = timeline.stream().mapToLong(DataCenterTimelineItemVO::entryCount).sum();
        long exitEventCount = timeline.stream().mapToLong(DataCenterTimelineItemVO::exitCount).sum();
        BigDecimal totalFee = filteredRecords.stream()
                .map(ParkingRecord::getFee)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        long averageDurationMinutes = resolveAverageDurationMinutes(filteredRecords);

        return new DataCenterSummaryVO(
                recordCount,
                activeRecordCount,
                exitedRecordCount,
                entryEventCount,
                exitEventCount,
                totalFee,
                averageDurationMinutes
        );
    }

    private long resolveAverageDurationMinutes(List<ParkingRecord> records) {
        long totalDurationMinutes = 0L;
        long countedRecords = 0L;

        for (ParkingRecord record : records) {
            long durationMinutes = resolveDurationMinutes(record);
            if (durationMinutes < 0) {
                continue;
            }
            totalDurationMinutes += durationMinutes;
            countedRecords++;
        }

        if (countedRecords == 0) {
            return 0L;
        }
        return Math.round((double) totalDurationMinutes / countedRecords);
    }

    private long resolveDurationMinutes(ParkingRecord record) {
        if (record.getDurationMinutes() != null) {
            return Math.max(record.getDurationMinutes(), 0);
        }
        if (record.getEntryTime() == null || record.getExitTime() == null) {
            return -1L;
        }
        return Math.max(Duration.between(record.getEntryTime(), record.getExitTime()).toMinutes(), 0L);
    }

    private String extractProvince(String plateNumber) {
        if (!StringUtils.hasText(plateNumber)) {
            return UNKNOWN_PROVINCE;
        }
        String trimmedPlate = plateNumber.trim();
        if (trimmedPlate.isEmpty()) {
            return UNKNOWN_PROVINCE;
        }

        char prefix = trimmedPlate.charAt(0);
        if (Character.UnicodeScript.of(prefix) == Character.UnicodeScript.HAN) {
            return String.valueOf(prefix);
        }
        return UNKNOWN_PROVINCE;
    }

    private String toStatusLabel(String status) {
        if (status == null) {
            return UNKNOWN_PROVINCE;
        }
        return switch (status) {
            case RECORD_STATUS_NOT_EXITED -> RECORD_STATUS_NOT_EXITED;
            case RECORD_STATUS_EXITED -> RECORD_STATUS_EXITED;
            default -> status;
        };
    }

    private List<ParkingRecordVO> loadExportRecords(DataCenterRecordQueryDTO queryDTO) {
        return loadFilteredRecords(queryDTO).stream()
                .map(this::toRecordVO)
                .toList();
    }

    private ParkingRecordQueryDTO toParkingQuery(DataCenterRecordQueryDTO source) {
        QueryRange range = resolveQueryRange(source);
        ParkingRecordQueryDTO target = new ParkingRecordQueryDTO();
        target.setPageNo(source.getPageNo());
        target.setPageSize(source.getPageSize());
        target.setSortField(source.getSortField());
        target.setSortOrder(source.getSortOrder());
        target.setPlateNumber(source.getPlateNumber());
        target.setStatuses(source.getStatuses());
        target.setParkNo(source.getParkNo());
        target.setStartTime(range.startTime());
        target.setEndTime(range.endTime());
        target.setAdvanced(source.isAdvanced());

        return target;
    }

    private QueryRange resolveQueryRange(DataCenterRecordQueryDTO source) {
        LocalDateTime now = LocalDateTime.now();
        String preset = source.getRangePreset() == null ? "LAST_30_DAYS" : source.getRangePreset().toUpperCase();

        LocalDateTime startTime;
        LocalDateTime endTime;

        switch (preset) {
            case "TODAY" -> {
                startTime = LocalDate.now().atStartOfDay();
                endTime = now;
            }
            case "THIS_WEEK" -> {
                LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                startTime = monday.atStartOfDay();
                endTime = now;
            }
            case "THIS_MONTH" -> {
                LocalDate firstDay = LocalDate.now().withDayOfMonth(1);
                startTime = firstDay.atStartOfDay();
                endTime = now;
            }
            case "CUSTOM" -> {
                if (source.getStartTime() == null || source.getEndTime() == null) {
                    throw new BusinessException(400, "Custom range requires start and end time");
                }
                startTime = source.getStartTime();
                endTime = source.getEndTime();
            }
            case "LAST_30_DAYS" -> {
                startTime = now.minusDays(30).with(LocalTime.MIN);
                endTime = now;
            }
            default -> throw new BusinessException(400, "Unsupported range preset");
        }

        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new BusinessException(400, "Start time must be earlier than end time");
        }
        return new QueryRange(startTime, endTime);
    }

    private ParkingRecordVO toRecordVO(ParkingRecord record) {
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

    private static final class TimelineCounter {
        private long entryCount;
        private long exitCount;
    }

    private record QueryRange(LocalDateTime startTime, LocalDateTime endTime) {
    }
}
