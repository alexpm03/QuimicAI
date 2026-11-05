package com.iaimagenes.backend.entrenamiento;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.iaimagenes.backend.analisis.AnalizadorFallos;
import com.iaimagenes.backend.analisis.Historial;
import com.iaimagenes.backend.lector.CombinadorLetras;
import com.iaimagenes.backend.lector.LectorLetras;
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

    /**
     * Uso:  EvaluarPruebas                                   -> examina ../modelo
     *       EvaluarPruebas modelo=../repes/propia_1 etiqueta=propia
     *       EvaluarPruebas modelo=../repes/propia_1 lector=../modelo_lector etiqueta=propia_lector
     * Opciones: modelo=carpeta (../modelo), examen=carpeta (../pruebas/examen), etiqueta=nombre,
     * que agrupa en el historial las repeticiones de un mismo experimento (por defecto, "sin_etiqueta"),
     * y lector=carpeta, que corrige a la red con las letras O y N leídas en la imagen.
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> opciones = leerOpciones(args);
        Path carpeta = Path.of(opciones.getOrDefault("examen", "../pruebas/examen"));
        String carpetaModelo = opciones.getOrDefault("modelo", "../modelo");
        String etiqueta = opciones.getOrDefault("etiqueta", "sin_etiqueta");
        String carpetaLector = opciones.get("lector");
        Path carpetaVista = Path.of("../pruebas/vista_red");
        Path historial = Path.of("../historial/examenes.csv");
        Files.createDirectories(carpetaVista);
        List<Caso> casos = leerCasos(carpeta);

        int aciertos = 0;
        Map<String, int[]> porFuente = new TreeMap<>();   // fuente -> {aciertos, total}
        List<AnalizadorFallos.Resultado> resultados = new ArrayList<>();
        String red;
        int cambiadasPorLector = 0;
        int falsasAlarmas = 0;
        try (ClasificadorService clasificador = new ClasificadorService(carpetaModelo);
                LectorLetras lector = carpetaLector == null ? null : new LectorLetras(Path.of(carpetaLector));
                NDManager manager = NDManager.newBaseManager()) {
            red = clasificador.config().arquitectura();
            CombinadorLetras combinador = lector == null ? null
                    : new CombinadorLetras(Path.of("../datos/compuestos_pubchem.csv"));
            System.out.println();
            for (Caso caso : casos) {
                Prediccion prediccion;
                try (InputStream entrada = Files.newInputStream(caso.imagen())) {
                    prediccion = clasificador.clasificar(entrada);
                }

                BufferedImage original = Preprocesado.aplanarSobreBlanco(ImageIO.read(caso.imagen().toFile()));
                if (lector != null) {
                    LectorLetras.Resultado letras = lector.leerImagen(original);
                    if (!combinador.algunoEncaja(letras)) {
                        // Todas las moléculas del examen están en la lista: cada aviso es una falsa alarma
                        falsasAlarmas++;
                    }
                    Prediccion corregida = combinador.reordenar(prediccion, letras);
                    if (!corregida.compuesto().equals(prediccion.compuesto())) {
                        cambiadasPorLector++;
                    }
                    prediccion = corregida;
                }
                ImageIO.write(Preprocesado.vistaDeLaRed(original, manager, clasificador.config().tamano()), "png",
                        carpetaVista.resolve(caso.esperado() + "__" + caso.fuente() + ".png").toFile());

                resultados.add(new AnalizadorFallos.Resultado(caso.esperado(), caso.fuente(),
                        prediccion.compuesto(), prediccion.confianza()));
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
        if (carpetaLector != null) {
            System.out.println("El lector de letras cambió la respuesta de la red en " + cambiadasPorLector + " imágenes.");
            System.out.println("Avisos de \"no está en mi lista\" (falsas alarmas, todas lo están): " + falsasAlarmas);
        }
        System.out.println("Lo que ve la red, en " + carpetaVista.toAbsolutePath().normalize());

        AnalizadorFallos analizador = new AnalizadorFallos(Path.of("../datos/compuestos_pubchem.csv"));
        AnalizadorFallos.Informe informe = analizador.analizar(resultados);
        AnalizadorFallos.imprimir(informe);
        Historial.anotar(historial, etiqueta, carpetaModelo, red, informe);
        Historial.resumirRepeticiones(historial, etiqueta);
        System.out.println();
        System.out.println("Anotado en " + historial.toAbsolutePath().normalize() + " con la etiqueta \"" + etiqueta + "\"");
    }

    /** Convierte argumentos "clave=valor" en un mapa. */
    private static Map<String, String> leerOpciones(String[] args) {
        Map<String, String> opciones = new HashMap<>();
        for (String arg : args) {
            int igual = arg.indexOf('=');
            if (igual < 0) {
                throw new IllegalArgumentException("Opción sin \"=\": " + arg + ". Usa modelo=..., examen=... o etiqueta=...");
            }
            opciones.put(arg.substring(0, igual), arg.substring(igual + 1));
        }
        return opciones;
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
