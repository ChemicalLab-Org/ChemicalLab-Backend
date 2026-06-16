package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import com.morales.chemicallab.dto.OxoanionResponse;
import com.morales.chemicallab.service.ChemicalEngineService;
import com.morales.chemicallab.service.ChemistryCatalogService;
import com.morales.chemicallab.dto.SaltRequest;
import com.morales.chemicallab.dto.OxisaltRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/chemistry")
public class ChemicalEngineController {

    private final ChemicalEngineService chemicalEngineService;
    private final ChemistryCatalogService chemistryCatalogService;

    public ChemicalEngineController(
            ChemicalEngineService chemicalEngineService,
            ChemistryCatalogService chemistryCatalogService
    ) {
        this.chemicalEngineService = chemicalEngineService;
        this.chemistryCatalogService = chemistryCatalogService;
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

    @PostMapping("/salts")
    public CompoundResponse generateSalt(@Valid @RequestBody SaltRequest request) {
        return chemicalEngineService.generateSalt(request);
    }

    @PostMapping("/oxisalts")
    public CompoundResponse generateOxisalt(@Valid @RequestBody OxisaltRequest request) {
        return chemicalEngineService.generateOxisalt(request);
    }

    /** Catálogo de aniones monoatómicos válidos para sales binarias. */
    @GetMapping("/catalog/binary-anions")
    public List<BinaryAnionResponse> binaryAnions() {
        return chemistryCatalogService.binaryAnions();
    }

    /** Catálogo de oxoaniones (grupos oxácidos) válidos para oxisales. */
    @GetMapping("/catalog/oxoanions")
    public List<OxoanionResponse> oxoanions() {
        return chemistryCatalogService.oxoanions();
    }
}