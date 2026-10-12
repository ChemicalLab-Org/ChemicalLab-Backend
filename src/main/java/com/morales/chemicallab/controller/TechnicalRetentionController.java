package com.morales.chemicallab.controller;
import com.morales.chemicallab.service.TechnicalRetentionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
@RestController @RequestMapping("/api/admin/technical-retention") @RequiredArgsConstructor
public class TechnicalRetentionController {
    private final TechnicalRetentionService retention;
    @PostMapping public TechnicalRetentionService.Result batch(@RequestParam LocalDateTime cutoff,@RequestParam(defaultValue="true") boolean simulation) {
        return retention.batch(cutoff,simulation);
    }
}
