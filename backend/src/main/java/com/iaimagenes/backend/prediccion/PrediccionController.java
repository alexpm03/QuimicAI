package com.iaimagenes.backend.prediccion;

import java.awt.image.BufferedImage;
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

    /**
     * Lo que recibe el portal: la predicción con nombre y fórmula ya resueltos y, si hay lector,
     * las letras que ha leído (null si no lo hay).
     */
    public record Respuesta(String compuesto, String nombre, String formula, String iupac, double confianza,
            List<Opcion> ranking, Letras letras) {

        public record Opcion(String compuesto, String nombre, double probabilidad) {}

        /**
         * O y N leídas en la imagen; redDecia es el nombre que daba la red sola si el lector lo cambió;
         * desconocida, que ninguna molécula de la lista tiene esas letras.
         */
        public record Letras(int oxigenos, int nitrogenos, String redDecia, boolean desconocida) {}
    }

    private final ClasificadorService clasificador;
    private final LectorService lector;
    private final CatalogoCompuestos catalogo;

    public PrediccionController(ClasificadorService clasificador, LectorService lector, CatalogoCompuestos catalogo) {
        this.clasificador = clasificador;
        this.lector = lector;
        this.catalogo = catalogo;
    }

    @PostMapping("/predict")
    public Respuesta predecir(@RequestParam("imagen") MultipartFile imagen) throws Exception {
        if (imagen.isEmpty()) {
            throw new IllegalArgumentException("No se ha enviado ninguna imagen.");
        }
        BufferedImage original = LecturaImagen.leer(imagen.getBytes());
        Prediccion red = clasificador.clasificar(original);
        LectorService.Corregida corregida = lector.corregir(red, original);
        Prediccion prediccion = corregida.prediccion();

        Respuesta.Letras letras = null;
        if (corregida.letras() != null) {
            String redDecia = red.compuesto().equals(prediccion.compuesto()) ? null
                    : catalogo.buscar(red.compuesto()).nombre();
            letras = new Respuesta.Letras(corregida.letras().oxigenos(), corregida.letras().nitrogenos(), redDecia,
                    corregida.desconocida());
        }

        CatalogoCompuestos.Compuesto mejor = catalogo.buscar(prediccion.compuesto());
        List<Respuesta.Opcion> ranking = prediccion.ranking().stream()
                .map(p -> new Respuesta.Opcion(p.compuesto(), catalogo.buscar(p.compuesto()).nombre(), p.probabilidad()))
                .toList();
        return new Respuesta(mejor.clave(), mejor.nombre(), mejor.formula(), mejor.iupac(), prediccion.confianza(),
                ranking, letras);
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
