package com.iaimagenes.backend.prediccion;

import java.util.List;

public record Prediccion(String compuesto, double confianza, List<Probabilidad> ranking) {

    public record Probabilidad(String compuesto, double probabilidad) {}
}
