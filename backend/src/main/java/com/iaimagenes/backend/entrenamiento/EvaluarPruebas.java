package com.iaimagenes.backend.entrenamiento;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.iaimagenes.backend.prediccion.ClasificadorService;
import com.iaimagenes.backend.prediccion.Prediccion;

import ai.djl.ndarray.NDManager;

/**
 * Examen con imágenes reales que la red no ha visto nunca.
 * Lee pruebas/examen/<clave>/*.png: el nombre de la carpeta es la respuesta correcta y el del archivo,
 * su procedencia (pubchem_grande, wiki_es...), para dar también el acierto por fuente.
 * Usa el mismo ClasificadorService que el backend, así que mide exactamente lo que verá el portal.
 *
 * Además guarda en pruebas/vista_red/ cada imagen tal y como entra en la red (en negativo),
 * para poder ver qué está mirando cuando se equivoca.
 */
public class EvaluarPruebas {

    record Caso(String esperado, String fuente, Path imagen) {}

    public static void main(String[] args) throws Exception {
        // Uso: EvaluarPruebas [carpeta del examen] [carpeta del modelo], por defecto ../pruebas/examen y ../modelo
        Path carpeta = Path.of(args.length > 0 ? args[0] : "../pruebas/examen");
        String carpetaModelo = args.length > 1 ? args[1] : "../modelo";
        Path carpetaVista = Path.of("../pruebas/vista_red");
        Files.createDirectories(carpetaVista);
        List<Caso> casos = leerCasos(carpeta);

        int aciertos = 0;
        Map<String, int[]> porFuente = new TreeMap<>();   // fuente -> {aciertos, total}
        try (ClasificadorService clasificador = new ClasificadorService(carpetaModelo);
                NDManager manager = NDManager.newBaseManager()) {
            System.out.println();
            for (Caso caso : casos) {
                Prediccion prediccion;
                try (InputStream entrada = Files.newInputStream(caso.imagen())) {
                    prediccion = clasificador.clasificar(entrada);
                }

                BufferedImage original = Preprocesado.aplanarSobreBlanco(ImageIO.read(caso.imagen().toFile()));
                ImageIO.write(Preprocesado.vistaDeLaRed(original, manager, clasificador.config().tamano()), "png",
                        carpetaVista.resolve(caso.esperado() + "__" + caso.fuente() + ".png").toFile());

                boolean acierto = prediccion.compuesto().equals(caso.esperado());
                int[] cuenta = porFuente.computeIfAbsent(caso.fuente(), f -> new int[2]);
                cuenta[1]++;
                if (acierto) {
                    aciertos++;
                    cuenta[0]++;
                }
                System.out.printf("%s  %-15s %-16s -> %-15s %5.1f %%%n", acierto ? "OK   " : "FALLO",
                        caso.esperado(), caso.fuente(), prediccion.compuesto(), prediccion.confianza() * 100);
            }
        }

        System.out.println();
        System.out.println("Por procedencia:");
        porFuente.forEach((fuente, c) ->
                System.out.printf("  %-16s %3d de %3d (%.1f %%)%n", fuente, c[0], c[1], 100.0 * c[0] / c[1]));
        System.out.printf("%nAciertos: %d de %d (%.1f %%)%n", aciertos, casos.size(), 100.0 * aciertos / casos.size());
        System.out.println("Lo que ve la red, en " + carpetaVista.toAbsolutePath().normalize());
    }

    /** Recorre las subcarpetas de cada compuesto; cada PNG es un caso del examen. */
    private static List<Caso> leerCasos(Path carpeta) throws Exception {
        try (Stream<Path> rutas = Files.walk(carpeta, 2)) {
            return rutas.filter(r -> r.toString().endsWith(".png"))
                    .filter(r -> !r.getParent().equals(carpeta))
                    .sorted()
                    .map(r -> new Caso(r.getParent().getFileName().toString(),
                            r.getFileName().toString().replace(".png", ""), r))
                    .toList();
        }
    }
}
