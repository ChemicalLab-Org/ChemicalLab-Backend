package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import com.morales.chemicallab.service.ChemicalEngineService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chemistry")
public class ChemicalEngineController {

    private final ChemicalEngineService chemicalEngineService;

    public ChemicalEngineController(ChemicalEngineService chemicalEngineService) {
        this.chemicalEngineService = chemicalEngineService;
    }

    @PostMapping("/oxides")
    public CompoundResponse generateOxide(@Valid @RequestBody ElementCompoundRequest request) {
        return chemicalEngineService.generateOxide(request);
    }

    @PostMapping("/hydroxides")
    public CompoundResponse generateHydroxide(@Valid @RequestBody ElementCompoundRequest request) {
        return chemicalEngineService.generateHydroxide(request);
    }

    @PostMapping("/acids")
    public CompoundResponse generateAcid(@Valid @RequestBody AcidRequest request) {
        return chemicalEngineService.generateAcid(request);
    }
}