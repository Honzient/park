package com.parking.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parking.common.PageResult;
import com.parking.common.exception.BusinessException;
import com.parking.domain.dto.recognition.RecognitionQueryDTO;
import com.parking.domain.entity.ParkingRecord;
import com.parking.domain.entity.ParkingSpot;
import com.parking.domain.entity.RecognitionRecord;
import com.parking.domain.vo.recognition.MediaRecognitionVO;
import com.parking.domain.vo.recognition.RecognitionRecordVO;
import com.parking.mapper.ParkingRecordMapper;
import com.parking.mapper.ParkingSpotMapper;
import com.parking.mapper.RecognitionRecordMapper;
import com.parking.security.SecurityUtils;
import com.parking.service.RecognitionService;
import com.parking.util.DateTimeUtils;
import com.parking.util.PlateNumberUtils;
import com.parking.util.QueryValidator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

@Service
public class RecognitionServiceImpl implements RecognitionService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionServiceImpl.class);

    private static final String CACHE_PREFIX = "cache:recognition:";
    private static final String PARKING_CACHE_PREFIX = "cache:parking:";
    private static final String RECORD_STATUS_NOT_EXITED = "\u672A\u51FA\u573A";
    private static final String RECORD_STATUS_EXITED = "\u5DF2\u51FA\u573A";
    private static final String SPOT_STATUS_FREE = "FREE";
    private static final String SPOT_STATUS_OCCUPIED = "OCCUPIED";
    private static final BigDecimal FIXED_HOURLY_RATE = new BigDecimal("5.00");
    private static final BigDecimal DAILY_FEE_CAP = new BigDecimal("120.00");
    private static final int DAILY_CAP_HOURS = 24;
    private static final char[] PROVINCE_CHARS = "\u4eac\u6d25\u6caa\u6e1d\u5180\u8c6b\u4e91\u8fbd\u9ed1\u6e58\u7696\u9c81\u65b0\u82cf\u6d59\u8d63\u9102\u6842\u7518\u664b\u8499\u9655\u5409\u95fd\u8d35\u7ca4\u9752\u85cf\u5ddd\u5b81\u743c".toCharArray();
    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LETTERS_DIGITS = "ABCDEFGHJKLMNPQRSTUVWXYZ0123456789";

    private final RecognitionRecordMapper recognitionRecordMapper;
    private final ParkingRecordMapper parkingRecordMapper;
    private final ParkingSpotMapper parkingSpotMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Environment springEnvironment;
    private final AtomicBoolean mockEngineOverrideWarned = new AtomicBoolean(false);
    private final AtomicBoolean modelOverrideWarned = new AtomicBoolean(false);
    private final Object inferenceApiStartupLock = new Object();
    private volatile Process inferenceApiProcess;

    @Value("${parking.recognition.engine:yolo11}")
    private String recognitionEngine = "mock";

    @Value("${parking.recognition.python-command:D:/Python/python.exe}")
    private String pythonCommand = "D:/Python/python.exe";

    @Value("${parking.recognition.script-path:smart-parking-ai/run_inference.py}")
    private String yoloScriptPath = "smart-parking-ai/run_inference.py";

    @Value("${parking.recognition.model-path:smart-parking-ai/models/yolo11n_plate.pt}")
    private String yoloModelPath = "smart-parking-ai/models/yolo11n_plate.pt";

    @Value("${parking.recognition.fallback-model:}")
    private String yoloFallbackModel = "";

    @Value("${parking.recognition.conf-threshold:0.45}")
    private double yoloConfThreshold = 0.45D;

    @Value("${parking.recognition.yolo-device:0}")
    private String yoloDevice = "0";

    @Value("${parking.recognition.ocr-device:gpu}")
    private String ocrDevice = "gpu";

    @Value("${parking.recognition.inference-api-base-url:http://127.0.0.1:8090}")
    private String inferenceApiBaseUrl = "http://127.0.0.1:8090";

    @Value("${parking.recognition.inference-api-connect-timeout-ms:5000}")
    private int inferenceApiConnectTimeoutMs = 5000;

    @Value("${parking.recognition.inference-api-read-timeout-ms:180000}")
    private int inferenceApiReadTimeoutMs = 180000;

    @Value("${parking.recognition.timeout-seconds:180}")
    private long processTimeoutSeconds = 180L;

    @Value("${parking.recognition.video-frame-step:12}")
    private int videoFrameStep = 12;

    @Value("${parking.recognition.video-max-frames:240}")
    private int videoMaxFrames = 240;

    @Value("${parking.recognition.enable-ocr:true}")
    private boolean enableOcr = true;

    @Value("${parking.recognition.yolo-config-dir:.ultralytics}")
    private String yoloConfigDir = ".ultralytics";

    @Value("${parking.recognition.paddle-home-dir:smart-parking-ai/.paddle}")
    private String paddleHomeDir = "smart-parking-ai/.paddle";

    @Value("${parking.recognition.cache-home-dir:smart-parking-ai/.cache}")
    private String cacheHomeDir = "smart-parking-ai/.cache";

    @Value("${parking.recognition.python-temp-dir:smart-parking-ai/.tmp}")
    private String pythonTempDir = "smart-parking-ai/.tmp";

    @Value("${parking.recognition.paddlex-home-dir:smart-parking-ai/.paddlex}")
    private String paddleXHomeDir = "smart-parking-ai/.paddlex";

    public RecognitionServiceImpl(RecognitionRecordMapper recognitionRecordMapper,
                                  ParkingRecordMapper parkingRecordMapper,
                                  ParkingSpotMapper parkingSpotMapper,
                                  StringRedisTemplate redisTemplate,
                                  ObjectMapper objectMapper,
                                  Environment springEnvironment) {
        this.recognitionRecordMapper = recognitionRecordMapper;
        this.parkingRecordMapper = parkingRecordMapper;
        this.parkingSpotMapper = parkingSpotMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.springEnvironment = springEnvironment;
    }

    @Override
    public PageResult<RecognitionRecordVO> queryRecords(RecognitionQueryDTO queryDTO) {
        QueryValidator.validateTimeRange(queryDTO.getStartTime(), queryDTO.getEndTime());

        String cacheKey = buildCacheKey(queryDTO);
        PageResult<RecognitionRecordVO> cached = getCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        Page<RecognitionRecord> page = new Page<>(queryDTO.getPageNo(), queryDTO.getPageSize());
        LambdaQueryWrapper<RecognitionRecord> wrapper = Wrappers.lambdaQuery();

        if (StringUtils.hasText(queryDTO.getRecognitionType())) {
            wrapper.eq(RecognitionRecord::getRecognitionType, queryDTO.getRecognitionType());
        }
        if (StringUtils.hasText(queryDTO.getPlateNumber())) {
            wrapper.like(RecognitionRecord::getPlateNumber, queryDTO.getPlateNumber());
        }
        if (queryDTO.getMinAccuracy() != null) {
            wrapper.ge(RecognitionRecord::getAccuracy, queryDTO.getMinAccuracy());
        }
        if (queryDTO.getStartTime() != null) {
            wrapper.ge(RecognitionRecord::getRecognitionTime, queryDTO.getStartTime());
        }
        if (queryDTO.getEndTime() != null) {
            wrapper.le(RecognitionRecord::getRecognitionTime, queryDTO.getEndTime());
        }

        applySort(wrapper, queryDTO.getSortField(), queryDTO.getSortOrder());
        Page<RecognitionRecord> resultPage = recognitionRecordMapper.selectPage(page, wrapper);

        List<RecognitionRecordVO> records = resultPage.getRecords().stream()
                .map(this::toVO)
                .toList();

        PageResult<RecognitionRecordVO> result = new PageResult<>(
                records,
                resultPage.getTotal(),
                resultPage.getCurrent(),
                resultPage.getSize()
        );
        putCache(cacheKey, result);
        return result;
    }

    @Override
    public byte[] exportExcel(RecognitionQueryDTO queryDTO) {
        List<RecognitionRecordVO> exportRecords = loadExportRecords(queryDTO);

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("识别记录");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("车牌号");
            header.createCell(1).setCellValue("识别时间");
            header.createCell(2).setCellValue("识别准确率（%）");
            header.createCell(3).setCellValue("识别类型");

            int rowIndex = 1;
            for (RecognitionRecordVO record : exportRecords) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(record.plateNumber());
                row.createCell(1).setCellValue(record.recognitionTime());
                row.createCell(2).setCellValue(record.accuracy().doubleValue());
                row.createCell(3).setCellValue(toRecognitionTypeLabel(record.recognitionType()));
            }

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (Exception exception) {
            throw new BusinessException(500, "导出识别记录表格失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaRecognitionVO recognizeImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "Image file is required");
        }
        String source = resolveSource(file.getOriginalFilename(), "uploaded-image");
        if (isMockEngine()) {
            return mockRecognition(source, "IMAGE");
        }

        InferencePayload payload = runYolo11Inference("IMAGE", file, null);
        return persistRecognition("IMAGE", payload.plateNumber(), payload.accuracy(), source);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MediaRecognitionVO recognizeVideo(MultipartFile file, String streamUrl) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasStreamUrl = StringUtils.hasText(streamUrl);
        if (!hasFile && !hasStreamUrl) {
            throw new BusinessException(400, "Video file or stream URL is required");
        }

        String source = hasFile ? resolveSource(file.getOriginalFilename(), "uploaded-video") : streamUrl.trim();
        if (isMockEngine()) {
            return mockRecognition(source, "VIDEO");
        }

        InferencePayload payload = runYolo11Inference("VIDEO", hasFile ? file : null, hasStreamUrl ? streamUrl.trim() : null);
        return persistRecognition("VIDEO", payload.plateNumber(), payload.accuracy(), source);
    }

    @Override
    public String cameraAccessGuide() {
        return "YOLOv11 + PaddleOCR pipeline: upload image/video or provide stream URL, backend forwards to FastAPI inference service, then stores recognition records.";
    }

    private boolean isMockEngine() {
        String configuredEngine = (recognitionEngine == null ? "" : recognitionEngine).trim();
        if ("mock".equalsIgnoreCase(configuredEngine) && !isTestLikeProfileActive()) {
            if (mockEngineOverrideWarned.compareAndSet(false, true)) {
                log.warn("Ignoring non-test recognition engine 'mock'; forcing YOLOv11 + PaddleOCR runtime.");
            }
            return false;
        }
        return "mock".equalsIgnoreCase(configuredEngine);
    }

    private InferencePayload runYolo11Inference(String type, MultipartFile file, String streamUrl) {
        try {
            Path model = resolveConfiguredPath(yoloModelPath);
            if (shouldUsePreferredPlateModel(model)) {
                Path preferredModel = resolveConfiguredPath("smart-parking-ai/models/yolo11n_plate.pt");
                if (preferredModel != null && Files.isRegularFile(preferredModel) && modelOverrideWarned.compareAndSet(false, true)) {
                    log.warn("Configured model '{}' looks like a generic YOLO checkpoint; ensure FastAPI uses preferred plate model '{}'.",
                            model, preferredModel);
                }
            }
            ensureFileExists(model, "YOLO model file", "PARKING_RECOGNITION_MODEL");

            if (StringUtils.hasText(yoloFallbackModel)) {
                Path fallbackModelPath = resolveConfiguredPath(yoloFallbackModel);
                ensureFileExists(fallbackModelPath, "YOLO fallback model file", "PARKING_RECOGNITION_FALLBACK_MODEL");
            }

            String endpoint = "VIDEO".equalsIgnoreCase(type) ? "/recognize/video" : "/recognize/image";
            String url = buildInferenceApiUrl(endpoint);
            RestTemplate restTemplate = buildInferenceRestTemplate();

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("conf", String.format(Locale.ROOT, "%.4f", yoloConfThreshold));
            if ("VIDEO".equalsIgnoreCase(type)) {
                body.add("videoFrameStep", String.valueOf(Math.max(videoFrameStep, 1)));
                body.add("videoMaxFrames", String.valueOf(Math.max(videoMaxFrames, 1)));
            }
            if (StringUtils.hasText(yoloDevice)) {
                body.add("yoloDevice", yoloDevice.trim());
            }
            if (StringUtils.hasText(ocrDevice)) {
                body.add("ocrDevice", ocrDevice.trim());
            }
            if (file != null && !file.isEmpty()) {
                String filename = StringUtils.hasText(file.getOriginalFilename())
                        ? file.getOriginalFilename().trim()
                        : ("IMAGE".equalsIgnoreCase(type) ? "input.jpg" : "input.mp4");
                byte[] payloadBytes = file.getBytes();
                ByteArrayResource resource = new ByteArrayResource(payloadBytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
                HttpHeaders fileHeaders = new HttpHeaders();
                fileHeaders.setContentType(resolveFileMediaType(file.getContentType(), type));
                body.add("file", new HttpEntity<>(resource, fileHeaders));
            }
            if (StringUtils.hasText(streamUrl)) {
                body.add("streamUrl", streamUrl.trim());
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            log.debug("Calling FastAPI inference service, mode={}, url={}, engine={}", type, url, recognitionEngine);
            try {
                ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
                return parseInferenceOutput(response.getBody(), response.getStatusCode().value());
            } catch (ResourceAccessException accessException) {
                boolean recovered = ensureInferenceApiReady(accessException);
                if (recovered) {
                    ResponseEntity<String> retryResponse = restTemplate.exchange(url, HttpMethod.POST, requestEntity, String.class);
                    return parseInferenceOutput(retryResponse.getBody(), retryResponse.getStatusCode().value());
                }
                throw buildInferenceApiUnavailable(accessException);
            }
        } catch (HttpStatusCodeException exception) {
            return parseInferenceOutput(exception.getResponseBodyAsString(StandardCharsets.UTF_8), exception.getStatusCode().value());
        } catch (ResourceAccessException exception) {
            throw buildInferenceApiUnavailable(exception);
        } catch (IOException exception) {
            throw new BusinessException(500, "Failed to read media payload for inference: " + exception.getMessage());
        }
    }

    private BusinessException buildInferenceApiUnavailable(ResourceAccessException exception) {
        int code = resolveInferenceApiErrorCode(exception.getMessage());
        String endpoint = buildInferenceApiUrl("/health");
        String detail = compactOutput(exception.getMessage());
        String message = "Cannot reach FastAPI inference service at " + endpoint
                + ". Backend runs on port 8080, but recognition inference uses a separate service port. "
                + "Please start FastAPI service or set PARKING_RECOGNITION_API_BASE_URL. detail: " + detail;
        return new BusinessException(code, message);
    }

    private boolean ensureInferenceApiReady(ResourceAccessException rootCause) {
        if (!isLocalInferenceApiTarget()) {
            return false;
        }
        if (isInferenceApiHealthy()) {
            return true;
        }
        synchronized (inferenceApiStartupLock) {
            if (isInferenceApiHealthy()) {
                return true;
            }

            if (inferenceApiProcess != null && inferenceApiProcess.isAlive()) {
                return waitForInferenceApiHealthy(Duration.ofSeconds(90));
            }

            try {
                Path aiRoot = resolveAiRoot();
                Path logOut = aiRoot.resolve("run.infer.api.auto.out.log");
                Path logErr = aiRoot.resolve("run.infer.api.auto.err.log");

                ProcessBuilder processBuilder = new ProcessBuilder(
                        resolvePythonExecutable(),
                        "-m", "uvicorn",
                        "inference_server:app",
                        "--host", resolveInferenceApiHost(),
                        "--port", String.valueOf(resolveInferenceApiPort())
                );
                processBuilder.directory(aiRoot.toFile());
                Map<String, String> env = processBuilder.environment();
                preparePythonRuntimeEnvironment(env);
                applyInferenceRuntimeEnv(env);
                processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(logOut.toFile()));
                processBuilder.redirectError(ProcessBuilder.Redirect.appendTo(logErr.toFile()));

                inferenceApiProcess = processBuilder.start();
                log.info("FastAPI inference service auto-start triggered, pid={}, endpoint={}",
                        inferenceApiProcess.pid(),
                        buildInferenceApiUrl("/health"));
                return waitForInferenceApiHealthy(Duration.ofSeconds(90));
            } catch (Exception startupException) {
                log.error("Failed to auto-start FastAPI inference service after resource access failure: {}",
                        compactOutput(rootCause.getMessage(), startupException.getMessage()),
                        startupException);
                return false;
            }
        }
    }

    private void applyInferenceRuntimeEnv(Map<String, String> env) {
        if (env == null) {
            return;
        }
        Path modelPath = resolveConfiguredPath(yoloModelPath);
        if (modelPath != null) {
            env.put("PARKING_RECOGNITION_MODEL", modelPath.toString());
        }
        if (StringUtils.hasText(yoloFallbackModel)) {
            Path fallbackModelPath = resolveConfiguredPath(yoloFallbackModel);
            if (fallbackModelPath != null) {
                env.put("PARKING_RECOGNITION_FALLBACK_MODEL", fallbackModelPath.toString());
            }
        }
        env.put("PARKING_RECOGNITION_CONF", String.format(Locale.ROOT, "%.4f", yoloConfThreshold));
        env.put("PARKING_RECOGNITION_ENABLE_OCR", String.valueOf(enableOcr));
        env.put("PARKING_RECOGNITION_VIDEO_FRAME_STEP", String.valueOf(Math.max(videoFrameStep, 1)));
        env.put("PARKING_RECOGNITION_VIDEO_MAX_FRAMES", String.valueOf(Math.max(videoMaxFrames, 1)));
        if (StringUtils.hasText(yoloDevice)) {
            env.put("PARKING_RECOGNITION_YOLO_DEVICE", yoloDevice.trim());
        }
        if (StringUtils.hasText(ocrDevice)) {
            env.put("PARKING_RECOGNITION_OCR_DEVICE", ocrDevice.trim());
        }
    }

    private boolean waitForInferenceApiHealthy(Duration timeout) {
        long timeoutMs = timeout == null ? 60_000L : Math.max(1_000L, timeout.toMillis());
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isInferenceApiHealthy()) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isInferenceApiHealthy();
    }

    private boolean isInferenceApiHealthy() {
        String healthUrl = buildInferenceApiUrl("/health");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int connectTimeout = Math.max(300, Math.min(inferenceApiConnectTimeoutMs, 1500));
        int readTimeout = Math.max(500, Math.min(inferenceApiReadTimeoutMs, 2500));
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        RestTemplate healthTemplate = new RestTemplate(requestFactory);
        try {
            ResponseEntity<String> response = healthTemplate.getForEntity(healthUrl, String.class);
            if (!response.getStatusCode().is2xxSuccessful()) {
                return false;
            }
            JsonNode payload = parseJsonFromOutput(response.getBody());
            return payload != null && "ok".equalsIgnoreCase(payload.path("status").asText(""));
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isLocalInferenceApiTarget() {
        String host = resolveInferenceApiHost().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    private String resolveInferenceApiHost() {
        URI uri = parseInferenceApiBaseUri();
        String host = uri == null ? "" : uri.getHost();
        if (!StringUtils.hasText(host)) {
            return "127.0.0.1";
        }
        return host.trim();
    }

    private int resolveInferenceApiPort() {
        URI uri = parseInferenceApiBaseUri();
        if (uri != null && uri.getPort() > 0) {
            return uri.getPort();
        }
        String scheme = uri == null ? "http" : uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        return 8090;
    }

    private URI parseInferenceApiBaseUri() {
        String base = StringUtils.hasText(inferenceApiBaseUrl)
                ? inferenceApiBaseUrl.trim()
                : "http://127.0.0.1:8090";
        if (!base.contains("://")) {
            base = "http://" + base;
        }
        try {
            return new URI(base);
        } catch (URISyntaxException exception) {
            log.warn("Invalid inference api base url '{}', fallback to localhost:8090", base);
            return URI.create("http://127.0.0.1:8090");
        }
    }

    private RestTemplate buildInferenceRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int connectTimeout = Math.max(1000, inferenceApiConnectTimeoutMs);
        long timeoutFromProcessConfig = Math.max(10L, processTimeoutSeconds) * 1000L;
        int readTimeout = Math.max(
                3000,
                Math.max(inferenceApiReadTimeoutMs, (int) Math.min(Integer.MAX_VALUE, timeoutFromProcessConfig))
        );
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        return new RestTemplate(requestFactory);
    }

    private String buildInferenceApiUrl(String endpointPath) {
        String base = StringUtils.hasText(inferenceApiBaseUrl)
                ? inferenceApiBaseUrl.trim()
                : "http://127.0.0.1:8090";
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = StringUtils.hasText(endpointPath) ? endpointPath.trim() : "";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private MediaType resolveFileMediaType(String rawType, String recognitionType) {
        if (StringUtils.hasText(rawType)) {
            try {
                return MediaType.parseMediaType(rawType.trim());
            } catch (Exception ignored) {
                // Fallback below.
            }
        }
        return "VIDEO".equalsIgnoreCase(recognitionType) ? MediaType.valueOf("video/mp4") : MediaType.IMAGE_JPEG;
    }

    private int resolveInferenceApiErrorCode(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("timed out") || normalized.contains("timeout")) {
            return 504;
        }
        return 503;
    }

    private void preparePythonRuntimeEnvironment(Map<String, String> env) {
        Path yoloConfigPath = ensureDirectory(resolveConfiguredPath(yoloConfigDir), "YOLO config directory");
        Path paddleHomePath = ensureDirectory(resolveConfiguredPath(paddleHomeDir), "Paddle home directory");
        Path cacheHomePath = ensureDirectory(resolveConfiguredPath(cacheHomeDir), "cache home directory");
        Path tmpPath = ensureDirectory(resolveConfiguredPath(pythonTempDir), "Python temp directory");
        Path paddleXHomePath = ensureDirectory(resolveConfiguredPath(paddleXHomeDir), "PaddleX home directory");
        Path userHomePath = resolveAiRoot();

        putEnvPath(env, "YOLO_CONFIG_DIR", yoloConfigPath);
        putEnvPath(env, "ULTRALYTICS_SETTINGS_DIR", yoloConfigPath);
        putEnvPath(env, "PADDLE_HOME", paddleHomePath);
        putEnvPath(env, "PADDLEX_HOME", paddleXHomePath);
        putEnvPath(env, "XDG_CACHE_HOME", cacheHomePath);
        putEnvPath(env, "HOME", userHomePath);
        putEnvPath(env, "USERPROFILE", userHomePath);
        putEnvPath(env, "TMPDIR", tmpPath);
        putEnvPath(env, "TMP", tmpPath);
        putEnvPath(env, "TEMP", tmpPath);
        env.put("PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK", "True");
        env.put("FLAGS_use_mkldnn", "0");
    }

    private void putEnvPath(Map<String, String> env, String key, Path value) {
        if (env == null || !StringUtils.hasText(key) || value == null) {
            return;
        }
        env.put(key, value.toString());
    }

    private Path ensureDirectory(Path directoryPath, String label) {
        if (directoryPath == null) {
            throw new BusinessException(500, label + " is not configured");
        }
        try {
            Files.createDirectories(directoryPath);
            return directoryPath;
        } catch (IOException exception) {
            throw new BusinessException(500, "Failed to initialize " + label + ": " + directoryPath);
        }
    }

    private Path resolveAiRoot() {
        Path projectRoot = resolveProjectRoot();
        Path aiRoot = projectRoot.resolve("smart-parking-ai").normalize();
        if (Files.isDirectory(aiRoot)) {
            return aiRoot;
        }
        return projectRoot;
    }

    private boolean isTestLikeProfileActive() {
        if (springEnvironment == null) {
            return false;
        }
        for (String profile : springEnvironment.getActiveProfiles()) {
            if (!StringUtils.hasText(profile)) {
                continue;
            }
            String normalized = profile.trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("test") || normalized.contains("mock")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldUsePreferredPlateModel(Path configuredModelPath) {
        if (isTestLikeProfileActive() || configuredModelPath == null) {
            return false;
        }
        String fileName = configuredModelPath.getFileName() == null
                ? ""
                : configuredModelPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!fileName.matches("^yolo11[nslmx]?\\.pt$")) {
            return false;
        }
        Path preferredModel = resolveConfiguredPath("smart-parking-ai/models/yolo11n_plate.pt");
        return preferredModel != null && !configuredModelPath.normalize().equals(preferredModel.normalize());
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    private String readFuture(Future<String> future, String streamLabel) throws InterruptedException {
        if (future == null) {
            return "";
        }
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            throw new BusinessException(500, "Timed out while reading python " + streamLabel + " output");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new BusinessException(500, "Failed to read python " + streamLabel + " output: " + cause.getMessage());
        }
    }

    private InferencePayload parseInferenceOutput(String responseBody, int httpStatus) {
        JsonNode root = parseJsonFromOutput(responseBody);
        if (root == null) {
            String fallbackMessage = compactOutput(responseBody);
            if (!StringUtils.hasText(fallbackMessage)) {
                fallbackMessage = "no json output captured";
            }
            throw new BusinessException(500, "Invalid YOLOv11 output: " + fallbackMessage);
        }

        String nestedDetailMessage = "";
        JsonNode detailNode = root.path("detail");
        if (detailNode.isObject()) {
            root = detailNode;
        } else if (detailNode.isTextual()) {
            nestedDetailMessage = detailNode.asText("");
        }

        String status = root.path("status").asText(httpStatus >= 400 ? "error" : "");
        if (!"success".equalsIgnoreCase(status) || httpStatus >= 400) {
            String message = root.path("message").asText("");
            if (!StringUtils.hasText(message)) {
                message = nestedDetailMessage;
            }
            if (!StringUtils.hasText(message)) {
                message = compactOutput(responseBody);
            }
            log.warn("YOLOv11 inference API failed, httpStatus={}, status={}, body={}",
                    httpStatus,
                    status,
                    compactOutput(responseBody));
            int code = httpStatus >= 400 && httpStatus < 600 ? httpStatus : inferInferenceFailureCode(message);
            throw new BusinessException(code, "YOLOv11 inference failed: " + message);
        }

        String plateNumber = root.path("plate_number").asText("");
        if (!StringUtils.hasText(plateNumber)) {
            log.warn("YOLOv11 inference returned success but empty plate number, body={}",
                    compactOutput(responseBody));
            throw new BusinessException(422, "YOLOv11 + PaddleOCR did not return a valid plate number");
        }

        double accuracyValue = root.path("accuracy").asDouble(0D);
        if (accuracyValue < 0D) {
            accuracyValue = 0D;
        }
        if (accuracyValue > 100D) {
            accuracyValue = 100D;
        }
        String yoloRuntimeDevice = root.path("yolo_device_runtime").asText(root.path("yolo_device_requested").asText("unknown"));
        String ocrRuntimeDevice = root.path("ocr_device_runtime").asText(root.path("ocr_device_requested").asText("unknown"));
        int detections = root.path("detections").asInt(0);
        int framesProcessed = root.path("frames_processed").asInt(0);
        String source = root.path("source").asText("");
        JsonNode timings = root.path("timings_ms");
        double modelLoadMs = timings.path("model_load").asDouble(-1D);
        double inferenceMs = timings.path("inference").asDouble(-1D);
        double detectMs = timings.path("detect").asDouble(-1D);
        double ocrMs = timings.path("ocr").asDouble(-1D);
        double endToEndMs = timings.path("end_to_end").asDouble(-1D);
        if (root.hasNonNull("ocr_warning")) {
            log.warn("PaddleOCR warning from inference script: {}", root.path("ocr_warning").asText(""));
        }
        log.info(
                "YOLOv11 inference success, yoloDevice={}, ocrDevice={}, framesProcessed={}, detections={}, modelLoadMs={}, inferenceMs={}, detectMs={}, ocrMs={}, endToEndMs={}, source={}",
                yoloRuntimeDevice,
                ocrRuntimeDevice,
                framesProcessed,
                detections,
                formatMs(modelLoadMs),
                formatMs(inferenceMs),
                formatMs(detectMs),
                formatMs(ocrMs),
                formatMs(endToEndMs),
                truncateSource(source)
        );
        BigDecimal accuracy = BigDecimal.valueOf(accuracyValue).setScale(2, RoundingMode.HALF_UP);
        return new InferencePayload(plateNumber, accuracy);
    }

    private int inferInferenceFailureCode(String message) {
        String normalized = message == null ? "" : message.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("no plate recognized") || normalized.contains("did not return a valid plate number")) {
            return 422;
        }
        if (normalized.contains("timed out")) {
            return 504;
        }
        return 500;
    }

    private JsonNode parseJsonFromOutput(String output) {
        if (!StringUtils.hasText(output)) {
            return null;
        }
        String trimmed = output.trim();
        try {
            return objectMapper.readTree(trimmed);
        } catch (Exception ignored) {
            // Continue trying line-by-line extraction.
        }

        String[] lines = trimmed.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (!line.startsWith("{") || !line.endsWith("}")) {
                continue;
            }
            try {
                return objectMapper.readTree(line);
            } catch (Exception ignored) {
                // Continue searching.
            }
        }
        return null;
    }

    private MediaRecognitionVO persistRecognition(String type, String plateCandidate, BigDecimal accuracy, String source) {
        String plateNumber = normalizePlate(plateCandidate);
        BigDecimal normalizedAccuracy = accuracy == null
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : accuracy.setScale(2, RoundingMode.HALF_UP);
        LocalDateTime recognizedAt = LocalDateTime.now();

        syncParkingFlowAfterRecognition(plateNumber, recognizedAt);

        RecognitionRecord record = new RecognitionRecord();
        record.setPlateNumber(plateNumber);
        record.setRecognitionType(type);
        record.setRecognitionTime(recognizedAt);
        record.setAccuracy(normalizedAccuracy);
        record.setSourceUrl(truncateSource(source));
        recognitionRecordMapper.insert(record);

        clearBusinessCaches();
        return new MediaRecognitionVO(type, plateNumber, normalizedAccuracy.doubleValue(), source, cameraAccessGuide());
    }

    private void syncParkingFlowAfterRecognition(String plateNumber, LocalDateTime recognizedAt) {
        ParkingRecord activeRecord = findActiveRecordByPlate(plateNumber);
        if (activeRecord == null) {
            openParkingRecord(plateNumber, recognizedAt);
            return;
        }
        closeParkingRecord(activeRecord, plateNumber, recognizedAt);
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

    private void openParkingRecord(String plateNumber, LocalDateTime recognizedAt) {
        ParkingSpot spot = chooseSpotForEntry(plateNumber);
        if (spot == null || !StringUtils.hasText(spot.getSpotNo())) {
            throw new BusinessException(409, "\u6682\u65E0\u7A7A\u95F2\u8F66\u4F4D");
        }
        String parkNo = spot.getSpotNo();

        ParkingRecord record = new ParkingRecord();
        record.setPlateNumber(plateNumber);
        record.setParkNo(parkNo);
        record.setEntryTime(recognizedAt);
        record.setExitTime(null);
        record.setDurationMinutes(null);
        record.setFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        record.setStatus(RECORD_STATUS_NOT_EXITED);
        parkingRecordMapper.insert(record);

        spot.setStatus(SPOT_STATUS_OCCUPIED);
        spot.setCurrentPlate(plateNumber);
        spot.setEntryTime(recognizedAt);
        spot.setRecordId(record.getId());
        parkingSpotMapper.updateById(spot);
    }

    private ParkingSpot chooseSpotForEntry(String plateNumber) {
        ParkingSpot existingSpot = findSpotByCurrentPlate(plateNumber);
        if (existingSpot != null) {
            return existingSpot;
        }
        return findFirstFreeSpot();
    }

    private void closeParkingRecord(ParkingRecord activeRecord, String plateNumber, LocalDateTime recognizedAt) {
        LocalDateTime entryTime = activeRecord.getEntryTime();
        if (entryTime == null) {
            ParkingSpot linkedSpot = findSpotByRecordId(activeRecord.getId());
            entryTime = linkedSpot != null && linkedSpot.getEntryTime() != null ? linkedSpot.getEntryTime() : recognizedAt;
            activeRecord.setEntryTime(entryTime);
        }

        activeRecord.setExitTime(recognizedAt);
        Integer durationMinutes = resolveDurationMinutes(entryTime, recognizedAt);
        activeRecord.setDurationMinutes(durationMinutes);
        activeRecord.setFee(calculateFeeByRule(durationMinutes));
        activeRecord.setStatus(RECORD_STATUS_EXITED);
        parkingRecordMapper.updateById(activeRecord);

        releaseSpotForExitedRecord(activeRecord, plateNumber);
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

    private ParkingSpot findFirstFreeSpot() {
        return parkingSpotMapper.selectList(new QueryWrapper<ParkingSpot>()
                        .eq("status", SPOT_STATUS_FREE)
                        .orderByAsc("spot_no")
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

    private void releaseSpotForExitedRecord(ParkingRecord record, String plateNumber) {
        ParkingSpot spot = findSpotByRecordId(record.getId());
        if (spot == null) {
            spot = findSpotByCurrentPlate(plateNumber);
        }
        if (spot == null) {
            spot = findSpotByNo(record.getParkNo());
        }
        if (spot == null) {
            return;
        }

        boolean linkedByRecordId = record.getId() != null && record.getId().equals(spot.getRecordId());
        boolean linkedByPlate = StringUtils.hasText(spot.getCurrentPlate()) && spot.getCurrentPlate().equals(plateNumber);
        if (!linkedByRecordId && !linkedByPlate) {
            return;
        }

        clearSpotOccupancy(spot.getId());
    }

    private void clearSpotOccupancy(Long spotId) {
        if (spotId == null) {
            return;
        }
        UpdateWrapper<ParkingSpot> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", spotId)
                .set("status", SPOT_STATUS_FREE)
                .set("current_plate", null)
                .set("entry_time", null)
                .set("record_id", null);
        parkingSpotMapper.update(null, updateWrapper);
    }

    private Integer resolveDurationMinutes(LocalDateTime entryTime, LocalDateTime exitTime) {
        if (entryTime == null || exitTime == null) {
            return null;
        }
        return (int) Math.max(Duration.between(entryTime, exitTime).toMinutes(), 0);
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

    private void clearBusinessCaches() {
        try {
            redisTemplate.delete(redisTemplate.keys(CACHE_PREFIX + "*"));
            redisTemplate.delete(redisTemplate.keys(PARKING_CACHE_PREFIX + "*"));
        } catch (Exception ignored) {
        }
    }

    private String normalizePlate(String plateCandidate) {
        if (!StringUtils.hasText(plateCandidate)) {
            throw new BusinessException(422, "未识别到车牌号");
        }

        String normalized = plateCandidate.trim().toUpperCase(Locale.ROOT).replaceAll("[^\\p{IsHan}A-Z0-9]", "");
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(422, "未识别到车牌号");
        }
        if (normalized.length() > 20) {
            normalized = normalized.substring(0, 20);
        }

        return PlateNumberUtils.normalizeAndValidateRequired(normalized);
    }

    private void ensureFileExists(Path filePath, String label, String envKey) {
        if (filePath == null || !Files.isRegularFile(filePath)) {
            throw new BusinessException(500, label + " not found: " + filePath + ". Please configure " + envKey + ".");
        }
        try {
            if (Files.size(filePath) <= 0L) {
                throw new BusinessException(500, label + " is empty: " + filePath + ". Please configure " + envKey + ".");
            }
        } catch (IOException exception) {
            throw new BusinessException(500, "Failed to read " + label + ": " + filePath);
        }
    }

    private Path resolveConfiguredPath(String rawPath) {
        if (!StringUtils.hasText(rawPath)) {
            return null;
        }

        Path configured = Paths.get(rawPath.trim());
        if (configured.isAbsolute()) {
            return configured.normalize();
        }

        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        Path projectRoot = resolveProjectRoot();

        Path candidateFromProject = projectRoot.resolve(configured).normalize();
        if (Files.exists(candidateFromProject)) {
            return candidateFromProject;
        }

        Path candidateFromUserDir = userDir.resolve(configured).normalize();
        if (Files.exists(candidateFromUserDir)) {
            return candidateFromUserDir;
        }

        return candidateFromProject;
    }

    private Path resolveProjectRoot() {
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        if (Files.isDirectory(userDir.resolve("smart-parking-server")) && Files.isDirectory(userDir.resolve("smart-parking-ai"))) {
            return userDir;
        }

        Path fileName = userDir.getFileName();
        if (fileName != null && "smart-parking-server".equals(fileName.toString())) {
            Path parent = userDir.getParent();
            if (parent != null && Files.isDirectory(parent.resolve("smart-parking-ai"))) {
                return parent;
            }
        }

        return userDir;
    }

    private String resolveFileExt(String filename, String defaultExt) {
        if (!StringUtils.hasText(filename)) {
            return defaultExt;
        }
        String name = filename.trim();
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return defaultExt;
        }
        String ext = name.substring(index);
        if (ext.length() > 8) {
            return defaultExt;
        }
        return ext;
    }

    private String resolveSource(String source, String fallback) {
        if (!StringUtils.hasText(source)) {
            return fallback;
        }
        return source.trim();
    }

    private String resolvePythonExecutable() {
        String configured = StringUtils.hasText(pythonCommand) ? pythonCommand.trim() : "D:/Python/python.exe";
        if (!"python".equalsIgnoreCase(configured)) {
            return configured;
        }
        Path windowsFallback = Paths.get("D:/Python/python.exe").toAbsolutePath().normalize();
        if (Files.isRegularFile(windowsFallback)) {
            return windowsFallback.toString();
        }
        return configured;
    }

    private String truncateSource(String source) {
        if (!StringUtils.hasText(source)) {
            return null;
        }
        String normalized = source.trim();
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private String compactOutput(String... outputs) {
        if (outputs == null || outputs.length == 0) {
            return "(empty output)";
        }
        StringBuilder merged = new StringBuilder();
        for (String output : outputs) {
            if (!StringUtils.hasText(output)) {
                continue;
            }
            if (!merged.isEmpty()) {
                merged.append(' ');
            }
            merged.append(output);
        }
        if (merged.isEmpty()) {
            return "(empty output)";
        }
        String compact = merged.toString().replaceAll("\\s+", " ").trim();
        if (compact.length() > 240) {
            return compact.substring(0, 240) + "...";
        }
        return compact;
    }

    private String formatMs(double value) {
        if (value < 0D) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void cleanupTempDirectory(Path tempDir) {
        try (Stream<Path> stream = Files.walk(tempDir)) {
            stream.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            log.debug("Failed to clean temporary recognition file: {}", path, exception);
                        }
                    });
        } catch (IOException exception) {
            log.debug("Failed to walk temporary recognition directory: {}", tempDir, exception);
        }
    }

    private MediaRecognitionVO mockRecognition(String source, String type) {
        String normalizedSource = source == null ? "source" : source;
        long seed = Integer.toUnsignedLong(normalizedSource.hashCode());
        String province = String.valueOf(PROVINCE_CHARS[(int) (seed % PROVINCE_CHARS.length)]);
        char cityCode = LETTERS.charAt((int) ((seed / PROVINCE_CHARS.length) % LETTERS.length()));
        boolean greenPlate = (seed % 2) == 0;
        String suffix = buildPlateSuffix(seed, greenPlate);
        String plateNumber = PlateNumberUtils.normalizeAndValidateRequired(province + cityCode + suffix);

        double accuracyValue = 82 + (seed % 1800) / 100.0;
        if (accuracyValue > 99.8) {
            accuracyValue = 99.8;
        }

        BigDecimal accuracy = BigDecimal.valueOf(accuracyValue).setScale(2, RoundingMode.HALF_UP);
        return persistRecognition(type, plateNumber, accuracy, normalizedSource);
    }

    private String buildPlateSuffix(long seed, boolean newEnergyVehicle) {
        long value = seed ^ 0x9E3779B97F4A7C15L;
        if (newEnergyVehicle) {
            value = value * 6364136223846793005L + 1442695040888963407L;
            char energyType = (Long.remainderUnsigned(value, 2) == 0) ? 'D' : 'F';

            value = value * 6364136223846793005L + 1442695040888963407L;
            char serial = LETTERS_DIGITS.charAt((int) Long.remainderUnsigned(value, LETTERS_DIGITS.length()));

            StringBuilder builder = new StringBuilder(6);
            builder.append(energyType).append(serial);
            for (int i = 0; i < 4; i++) {
                value = value * 6364136223846793005L + 1442695040888963407L;
                builder.append((char) ('0' + Long.remainderUnsigned(value, 10)));
            }
            return builder.toString();
        }

        StringBuilder builder = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            value = value * 6364136223846793005L + 1442695040888963407L;
            int index = (int) Long.remainderUnsigned(value, LETTERS_DIGITS.length());
            builder.append(LETTERS_DIGITS.charAt(index));
        }
        return builder.toString();
    }

    private List<RecognitionRecordVO> loadExportRecords(RecognitionQueryDTO queryDTO) {
        RecognitionQueryDTO exportQuery = copyQuery(queryDTO);
        exportQuery.setPageNo(1);
        exportQuery.setPageSize(1000);

        PageResult<RecognitionRecordVO> firstPage = queryRecords(exportQuery);
        List<RecognitionRecordVO> result = new ArrayList<>(firstPage.records());
        long total = firstPage.total();
        long totalPages = (long) Math.ceil((double) total / exportQuery.getPageSize());

        for (long pageNo = 2; pageNo <= totalPages && pageNo <= 5; pageNo++) {
            exportQuery.setPageNo(pageNo);
            result.addAll(queryRecords(exportQuery).records());
        }
        return result;
    }

    private RecognitionQueryDTO copyQuery(RecognitionQueryDTO source) {
        RecognitionQueryDTO copy = new RecognitionQueryDTO();
        copy.setRecognitionType(source.getRecognitionType());
        copy.setStartTime(source.getStartTime());
        copy.setEndTime(source.getEndTime());
        copy.setMinAccuracy(source.getMinAccuracy() == null ? new BigDecimal("90") : source.getMinAccuracy());
        copy.setPlateNumber(source.getPlateNumber());
        copy.setSortField(source.getSortField());
        copy.setSortOrder(source.getSortOrder());
        copy.setAdvanced(source.isAdvanced());
        return copy;
    }

    private RecognitionRecordVO toVO(RecognitionRecord record) {
        return new RecognitionRecordVO(
                record.getId(),
                record.getPlateNumber(),
                DateTimeUtils.format(record.getRecognitionTime()),
                record.getAccuracy(),
                record.getRecognitionType(),
                record.getSourceUrl()
        );
    }

    private String toRecognitionTypeLabel(String recognitionType) {
        if ("VIDEO".equalsIgnoreCase(recognitionType)) {
            return "视频";
        }
        if ("IMAGE".equalsIgnoreCase(recognitionType)) {
            return "图片";
        }
        return recognitionType == null ? "未知" : recognitionType;
    }

    private void applySort(LambdaQueryWrapper<RecognitionRecord> wrapper, String sortField, String sortOrder) {
        boolean asc = "asc".equalsIgnoreCase(sortOrder);
        String normalizedField = normalize(sortField);

        switch (normalizedField) {
            case "platenumber" -> wrapper.orderBy(true, asc, RecognitionRecord::getPlateNumber);
            case "accuracy" -> wrapper.orderBy(true, asc, RecognitionRecord::getAccuracy);
            case "recognitiontype" -> wrapper.orderBy(true, asc, RecognitionRecord::getRecognitionType);
            case "recognitiontime" -> wrapper.orderBy(true, asc, RecognitionRecord::getRecognitionTime);
            default -> wrapper.orderByDesc(RecognitionRecord::getRecognitionTime);
        }
    }

    private String normalize(String field) {
        if (!StringUtils.hasText(field)) {
            return "recognitiontime";
        }
        return field.replace("_", "").toLowerCase();
    }

    private String buildCacheKey(RecognitionQueryDTO queryDTO) {
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

    private PageResult<RecognitionRecordVO> getCache(String cacheKey) {
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

    private void putCache(String cacheKey, PageResult<RecognitionRecordVO> result) {
        try {
            redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), Duration.ofSeconds(30));
        } catch (Exception ignored) {
        }
    }

    private record InferencePayload(String plateNumber, BigDecimal accuracy) {
    }
}
