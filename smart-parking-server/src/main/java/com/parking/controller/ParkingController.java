package com.parking.controller;

import com.parking.annotation.QueryLog;
import com.parking.common.ApiResponse;
import com.parking.common.PageResult;
import com.parking.domain.dto.parking.ParkingRecordQueryDTO;
import com.parking.domain.dto.parking.ParkingRecordUpdateDTO;
import com.parking.domain.dto.parking.SpotAssignDTO;
import com.parking.domain.dto.parking.SpotStatusUpdateDTO;
import com.parking.domain.vo.parking.AssignmentVehicleVO;
import com.parking.domain.vo.parking.ParkingRecordVO;
import com.parking.domain.vo.parking.ParkingSpotVO;
import com.parking.service.ParkingService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/parking")
public class ParkingController {

    private final ParkingService parkingService;

    public ParkingController(ParkingService parkingService) {
        this.parkingService = parkingService;
    }

    @PostMapping("/records/query")
    @PreAuthorize("hasAuthority('parking:query')")
    @QueryLog(module = "PARKING_RECORD_QUERY")
    public ApiResponse<PageResult<ParkingRecordVO>> query(@Valid @RequestBody ParkingRecordQueryDTO queryDTO) {
        return ApiResponse.success(parkingService.queryRecords(queryDTO));
    }

    @GetMapping("/records/{id}")
    @PreAuthorize("hasAuthority('parking:query')")
    public ApiResponse<ParkingRecordVO> detail(@PathVariable Long id) {
        return ApiResponse.success(parkingService.getDetail(id));
    }

    @PutMapping("/records/{id}")
    @PreAuthorize("hasAuthority('parking:query')")
    public ApiResponse<Void> updateRecord(@PathVariable Long id, @Valid @RequestBody ParkingRecordUpdateDTO updateDTO) {
        parkingService.updateRecord(id, updateDTO);
        return ApiResponse.success("Parking record updated", null);
    }

    @PostMapping("/records/{id}")
    @PreAuthorize("hasAuthority('parking:query')")
    public ApiResponse<Void> updateRecordByPost(@PathVariable Long id, @Valid @RequestBody ParkingRecordUpdateDTO updateDTO) {
        parkingService.updateRecord(id, updateDTO);
        return ApiResponse.success("Parking record updated", null);
    }

    @GetMapping("/spots")
    @PreAuthorize("hasAuthority('parking:assign')")
    public ApiResponse<List<ParkingSpotVO>> spots() {
        return ApiResponse.success(parkingService.listSpots());
    }

    @GetMapping("/spots/vehicles")
    @PreAuthorize("hasAuthority('parking:assign')")
    public ApiResponse<List<AssignmentVehicleVO>> assignmentVehicles() {
        return ApiResponse.success(parkingService.listAssignmentVehicles());
    }

    @PostMapping("/spots/assign")
    @PreAuthorize("hasAuthority('parking:assign')")
    public ApiResponse<Void> assign(@Valid @RequestBody SpotAssignDTO assignDTO) {
        parkingService.assignSpot(assignDTO);
        return ApiResponse.success("Spot assigned", null);
    }

    @PostMapping("/spots/status")
    @PreAuthorize("hasAuthority('parking:assign')")
    public ApiResponse<Void> updateSpotStatus(@Valid @RequestBody SpotStatusUpdateDTO updateDTO) {
        parkingService.updateSpotStatus(updateDTO);
        return ApiResponse.success("Spot status updated", null);
    }
}
