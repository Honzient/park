package com.parking.service;

import com.parking.common.PageResult;
import com.parking.domain.dto.recognition.RecognitionQueryDTO;
import com.parking.domain.vo.recognition.MediaRecognitionVO;
import com.parking.domain.vo.recognition.RecognitionRecordVO;
import org.springframework.web.multipart.MultipartFile;

public interface RecognitionService {

    PageResult<RecognitionRecordVO> queryRecords(RecognitionQueryDTO queryDTO);

    byte[] exportExcel(RecognitionQueryDTO queryDTO);

    MediaRecognitionVO recognizeImage(MultipartFile file);

    MediaRecognitionVO recognizeVideo(MultipartFile file, String streamUrl);

    String cameraAccessGuide();
}
