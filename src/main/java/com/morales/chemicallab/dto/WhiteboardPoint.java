package com.morales.chemicallab.dto;

/**
 * Coordenada de un trazo en el lienzo. Las coordenadas son relativas al canvas del cliente;
 * el backend solo valida que estén presentes y acota su cantidad por evento.
 */
public record WhiteboardPoint(Double x, Double y) {
}
