package com.morales.chemicallab.service;

import com.morales.chemicallab.dto.AcidRequest;
import com.morales.chemicallab.dto.CompoundResponse;
import com.morales.chemicallab.dto.ElementCompoundRequest;
import org.springframework.stereotype.Service;

@Service
public class ChemicalEngineService {

    public CompoundResponse generateOxide(ElementCompoundRequest request) {
        validateElementRequest(request);

        String formula = buildFormula(
                request.elementSymbol(),
                request.valence(),
                false,
                "O",
                2,
                false
        );

        String name = "óxido de " + request.elementName();

        String explanation = "El óxido se forma combinando el elemento "
                + request.elementName()
                + " con oxígeno. El oxígeno trabaja con valencia 2 y el elemento seleccionado trabaja con valencia "
                + request.valence()
                + ".";

        return new CompoundResponse(
                true,
                "Óxido",
                formula,
                name,
                explanation
        );
    }

    public CompoundResponse generateHydroxide(ElementCompoundRequest request) {
        validateElementRequest(request);

        String formula = buildFormula(
                request.elementSymbol(),
                request.valence(),
                false,
                "OH",
                1,
                true
        );

        String name = "hidróxido de " + request.elementName();

        String explanation = "El hidróxido se forma combinando un metal con el grupo hidroxilo OH. "
                + "El grupo OH trabaja con carga -1 y el elemento seleccionado trabaja con valencia "
                + request.valence()
                + ".";

        return new CompoundResponse(
                true,
                "Hidróxido",
                formula,
                name,
                explanation
        );
    }

    public CompoundResponse generateAcid(AcidRequest request) {
        validateAcidRequest(request);

        String formula = buildFormula(
                "H",
                1,
                false,
                request.anionFormula(),
                request.anionCharge(),
                request.anionFormula().length() > 1
        );

        String explanation = "El ácido se forma combinando hidrógeno con el anión "
                + request.anionName()
                + ". El hidrógeno trabaja con carga +1 y el anión trabaja con carga -"
                + request.anionCharge()
                + ".";

        return new CompoundResponse(
                true,
                "Ácido",
                formula,
                request.acidName(),
                explanation
        );
    }

    private String buildFormula(
            String cationFormula,
            int cationCharge,
            boolean cationIsGroup,
            String anionFormula,
            int anionCharge,
            boolean anionIsGroup
    ) {
        int gcd = gcd(cationCharge, anionCharge);

        int cationSubscript = anionCharge / gcd;
        int anionSubscript = cationCharge / gcd;

        return formatFormulaPart(cationFormula, cationSubscript, cationIsGroup)
                + formatFormulaPart(anionFormula, anionSubscript, anionIsGroup);
    }

    private String formatFormulaPart(String formula, int subscript, boolean isGroup) {
        if (subscript == 1) {
            return formula;
        }

        if (isGroup) {
            return "(" + formula + ")" + subscript;
        }

        return formula + subscript;
    }

    private int gcd(int a, int b) {
        if (b == 0) {
            return a;
        }

        return gcd(b, a % b);
    }

    private void validateElementRequest(ElementCompoundRequest request) {
        if (request.elementSymbol() == null || request.elementSymbol().isBlank()) {
            throw new IllegalArgumentException("El símbolo del elemento es obligatorio.");
        }

        if (request.elementName() == null || request.elementName().isBlank()) {
            throw new IllegalArgumentException("El nombre del elemento es obligatorio.");
        }

        if (request.valence() == null || request.valence() <= 0) {
            throw new IllegalArgumentException("La valencia debe ser mayor a cero.");
        }
    }

    private void validateAcidRequest(AcidRequest request) {
        if (request.anionFormula() == null || request.anionFormula().isBlank()) {
            throw new IllegalArgumentException("La fórmula del anión es obligatoria.");
        }

        if (request.anionName() == null || request.anionName().isBlank()) {
            throw new IllegalArgumentException("El nombre del anión es obligatorio.");
        }

        if (request.acidName() == null || request.acidName().isBlank()) {
            throw new IllegalArgumentException("El nombre del ácido es obligatorio.");
        }

        if (request.anionCharge() == null || request.anionCharge() <= 0) {
            throw new IllegalArgumentException("La carga del anión debe ser mayor a cero.");
        }
    }
}