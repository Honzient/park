package com.parking.controller;

import com.parking.annotation.QueryLog;
import com.parking.common.ApiResponse;
import com.parking.common.PageResult;
import com.parking.domain.dto.recognition.RecognitionQueryDTO;
import com.parking.domain.vo.recognition.MediaRecognitionVO;
import com.parking.domain.vo.recognition.RecognitionRecordVO;
import com.parking.service.RecognitionService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/recognition")
public class RecognitionController {

    private final RecognitionService recognitionService;

    public RecognitionController(RecognitionService recognitionService) {
        this.recognitionService = recognitionService;
    }

    @PostMapping("/records/query")
    @PreAuthorize("hasAuthority('recognition:query')")
    @QueryLog(module = "RECOGNITION_RECORD_QUERY")
    public ApiResponse<PageResult<RecognitionRecordVO>> query(@Valid @RequestBody RecognitionQueryDTO queryDTO) {
        return ApiResponse.success(recognitionService.queryRecords(queryDTO));
    }

    @PostMapping("/records/export/excel")
    @PreAuthorize("hasAuthority('recognition:query')")
    public ResponseEntity<ByteArrayResource> exportExcel(@RequestBody RecognitionQueryDTO queryDTO) {
        byte[] fileData = recognitionService.exportExcel(queryDTO);
        String filename = "\u8bc6\u522b\u8bb0\u5f55-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + ".xlsx";
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(fileData.length)
                .body(new ByteArrayResource(fileData));
    }

    @PostMapping(value = "/image/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('recognition:image')")
    public ApiResponse<MediaRecognitionVO> imageAnalyze(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success(recognitionService.recognizeImage(file));
    }

    @PostMapping(value = "/video/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('recognition:video')")
    public ApiResponse<MediaRecognitionVO> videoAnalyze(@RequestParam(value = "file", required = false) MultipartFile file,
                                                        @RequestParam(value = "streamUrl", required = false) String streamUrl) {
        return ApiResponse.success(recognitionService.recognizeVideo(file, streamUrl));
    }

    @GetMapping("/video/access-guide")
    @PreAuthorize("hasAuthority('recognition:video')")
    public ApiResponse<String> videoAccessGuide() {
        return ApiResponse.success(recognitionService.cameraAccessGuide());
    }
}
