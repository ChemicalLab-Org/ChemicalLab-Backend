package com.morales.chemicallab.controller;

import com.morales.chemicallab.dto.AcidNonMetalResponse;
import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.BinaryAnionResponse;
import com.morales.chemicallab.dto.CentralElementResponse;
import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import com.morales.chemicallab.dto.MetalResponse;
import com.morales.chemicallab.dto.OxisaltRequest;
import com.morales.chemicallab.dto.OxoanionResponse;
import com.morales.chemicallab.dto.SaltRequest;
import com.morales.chemicallab.service.ChemicalEngineService;
import com.morales.chemicallab.service.ChemistryCatalogService;
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

    // ===== Formación de compuestos =====

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

    // ===== Catálogos (fuente de verdad para el frontend) =====

    /** Catálogo de metales con sus valencias permitidas. */
    @GetMapping("/catalog/metals")
    public List<MetalResponse> metals() {
        return chemistryCatalogService.metals();
    }

    /** Catálogo de aniones monoatómicos válidos para sales binarias. */
    @GetMapping("/catalog/binary-nonmetals")
    public List<BinaryAnionResponse> binaryNonMetals() {
        return chemistryCatalogService.binaryAnions();
    }

    /** Catálogo de no metales que forman ácido hidrácido. */
    @GetMapping("/catalog/acid-nonmetals")
    public List<AcidNonMetalResponse> acidNonMetals() {
        return chemistryCatalogService.acidNonMetals();
    }

    /** Catálogo de elementos centrales presentes en los oxoaniones. */
    @GetMapping("/catalog/oxoanion-central-elements")
    public List<CentralElementResponse> oxoanionCentralElements() {
        return chemistryCatalogService.oxoanionCentralElements();
    }

    /**
     * Catálogo de oxoaniones (grupos oxácidos). Si se indica {@code centralElement}
     * se filtran los del elemento central correspondiente.
     */
    @GetMapping("/catalog/oxoanions")
    public List<OxoanionResponse> oxoanions(@RequestParam(required = false) String centralElement) {
        if (centralElement == null || centralElement.isBlank()) {
            return chemistryCatalogService.oxoanions();
        }
        return chemistryCatalogService.oxoanions(centralElement);
    }
}
