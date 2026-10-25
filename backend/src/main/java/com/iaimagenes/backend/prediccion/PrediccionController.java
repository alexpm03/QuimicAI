package com.iaimagenes.backend.prediccion;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class PrediccionController {

    /** Lo que recibe el portal: la predicción de la red con nombre y fórmula ya resueltos. */
    public record Respuesta(
            String compuesto, String nombre, String formula, String iupac, double confianza, List<Opcion> ranking) {

        public record Opcion(String compuesto, String nombre, double probabilidad) {}
    }

    private final ClasificadorService clasificador;
    private final CatalogoCompuestos catalogo;

    public PrediccionController(ClasificadorService clasificador, CatalogoCompuestos catalogo) {
        this.clasificador = clasificador;
        this.catalogo = catalogo;
    }

    @PostMapping("/predict")
    public Respuesta predecir(@RequestParam("imagen") MultipartFile imagen) throws Exception {
        if (imagen.isEmpty()) {
            throw new IllegalArgumentException("No se ha enviado ninguna imagen.");
        }
        Prediccion prediccion;
        try (InputStream entrada = imagen.getInputStream()) {
            prediccion = clasificador.clasificar(entrada);
        }

        CatalogoCompuestos.Compuesto mejor = catalogo.buscar(prediccion.compuesto());
        List<Respuesta.Opcion> ranking = prediccion.ranking().stream()
                .map(p -> new Respuesta.Opcion(p.compuesto(), catalogo.buscar(p.compuesto()).nombre(), p.probabilidad()))
                .toList();
        return new Respuesta(
                mejor.clave(), mejor.nombre(), mejor.formula(), mejor.iupac(), prediccion.confianza(), ranking);
    }

    /** Los compuestos que reconoce el modelo, para que el portal no tenga que tenerlos escritos a mano. */
    @GetMapping("/compuestos")
    public List<CatalogoCompuestos.Compuesto> compuestos() {
        return catalogo.todos();
    }

    /** Errores causados por lo que envía el usuario: 400 con un mensaje que el portal enseña tal cual. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> peticionIncorrecta(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
