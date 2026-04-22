package com.parking.controller;

import com.parking.annotation.QueryLog;
import com.parking.common.ApiResponse;
import com.parking.common.PageResult;
import com.parking.domain.dto.datacenter.DataCenterRecordQueryDTO;
import com.parking.domain.vo.datacenter.DataCenterOverviewVO;
import com.parking.domain.vo.parking.ParkingRecordVO;
import com.parking.service.DataCenterService;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
@RestController
@RequestMapping("/api/datacenter")
public class DataCenterController {

    private final DataCenterService dataCenterService;

    public DataCenterController(DataCenterService dataCenterService) {
        this.dataCenterService = dataCenterService;
    }

    @PostMapping("/records/query")
    @PreAuthorize("hasAuthority('datacenter:query')")
    @QueryLog(module = "DATACENTER_RECORD_QUERY")
    public ApiResponse<PageResult<ParkingRecordVO>> query(@RequestBody DataCenterRecordQueryDTO queryDTO) {
        return ApiResponse.success(dataCenterService.queryRecords(queryDTO));
    }

    @PostMapping("/records/export/excel")
    @PreAuthorize("hasAuthority('datacenter:export:excel')")
    public ResponseEntity<ByteArrayResource> exportExcel(@RequestBody DataCenterRecordQueryDTO queryDTO) {
        byte[] fileData = dataCenterService.exportExcel(queryDTO);
        String filename = "\u6570\u636e\u4e2d\u5fc3\u505c\u8f66\u8bb0\u5f55-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(fileData.length)
                .body(new ByteArrayResource(fileData));
    }

    @PostMapping("/records/export/pdf")
    @PreAuthorize("hasAuthority('datacenter:export:pdf')")
    public ResponseEntity<ByteArrayResource> exportPdf(@RequestBody DataCenterRecordQueryDTO queryDTO) {
        byte[] fileData = dataCenterService.exportPdf(queryDTO);
        String filename = "\u6570\u636e\u4e2d\u5fc3\u505c\u8f66\u8bb0\u5f55-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".pdf";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(fileData.length)
                .body(new ByteArrayResource(fileData));
    }

    @PostMapping("/overview")
    @PreAuthorize("hasAuthority('datacenter:query')")
    public ApiResponse<DataCenterOverviewVO> overview(@RequestBody DataCenterRecordQueryDTO queryDTO) {
        return ApiResponse.success(dataCenterService.overview(queryDTO));
    }
}
