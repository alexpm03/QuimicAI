package com.iaimagenes.backend.laboratorio;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints del laboratorio: inspeccionar y gestionar la red neuronal desde el portal. */
@RestController
@RequestMapping("/api/laboratorio")
public class LaboratorioController {

    private final InspectorRed inspector;

    public LaboratorioController(InspectorRed inspector) {
        this.inspector = inspector;
    }

    @GetMapping("/arquitectura")
    public InspectorRed.Arquitectura arquitectura() throws Exception {
        return inspector.describir();
    }
}
