package com.iaimagenes.backend.lector;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.iaimagenes.backend.entrenamiento.Preprocesado;
import com.iaimagenes.backend.lector.LectorLetras.LetraLeida;
import com.iaimagenes.backend.lector.LectorLetras.Resultado;

/**
 * Prueba del lector completo con el examen. Mide dos cosas:
 *
 *   - lo que importa al final: en cuántas imágenes cuenta bien las O y las N (comparado con el SMILES)
 *   - letra a letra, en las imágenes de PubChem, donde el color dice la respuesta: roja = O, azul = N, gris = H
 *
 * Guarda en pruebas/lector_lectura/ cada imagen con la letra leída encima de cada recuadro.
 */
public class ProbarLector {

    public static void main(String[] args) throws Exception {
        Path examen = Path.of("../pruebas/examen");
        Path salida = Path.of("../pruebas/lector_lectura");
        Files.createDirectories(salida);
        Map<String, int[]> reales = CombinadorLetras.letrasPorCompuesto(Path.of("../datos/compuestos_pubchem.csv"));

        Map<String, int[]> porFuente = new TreeMap<>();      // fuente -> {cuenta exacta, imágenes}
        Map<String, int[]> porColor = new TreeMap<>();       // letra esperada por color -> {bien leídas, total}
        Map<String, Integer> confusiones = new TreeMap<>();  // "esperada -> leída"
        List<Path> imagenes;
        try (Stream<Path> rutas = Files.walk(examen, 2)) {
            imagenes = rutas.filter(r -> r.toString().endsWith(".png")).sorted().toList();
        }

        System.out.println();
        System.out.println("Imagen                            O leídas-reales  N leídas-reales   letras leídas");
        try (LectorLetras lector = new LectorLetras(Path.of("../modelo_lector"))) {
            for (Path ruta : imagenes) {
                String clave = ruta.getParent().getFileName().toString();
                String fuente = ruta.getFileName().toString().replace(".png", "");
                BufferedImage imagen = Preprocesado.aplanarSobreBlanco(ImageIO.read(ruta.toFile()));
                Resultado resultado = lector.leerImagen(imagen);
                int[] on = reales.get(clave);
                boolean exacta = resultado.oxigenos() == on[0] && resultado.nitrogenos() == on[1];
                int[] cuenta = porFuente.computeIfAbsent(fuente, f -> new int[2]);
                cuenta[0] += exacta ? 1 : 0;
                cuenta[1]++;

                if (fuente.startsWith("pubchem")) {
                    for (LetraLeida l : resultado.letras()) {
                        String esperada = letraPorColor(l);
                        if (esperada != null) {
                            int[] c = porColor.computeIfAbsent(esperada, e -> new int[2]);
                            c[1]++;
                            if (esperada.equals(l.letra())) {
                                c[0]++;
                            } else {
                                confusiones.merge(esperada + " -> " + l.letra(), 1, Integer::sum);
                            }
                        }
                    }
                }

                StringBuilder leidas = new StringBuilder();
                for (LetraLeida l : resultado.letras()) {
                    leidas.append(l.letra()).append(l.multiplicador() > 1 ? String.valueOf(l.multiplicador()) : "").append(' ');
                }
                System.out.printf("%-33s   %2d-%-2d          %2d-%-2d   %s  %s%n", clave + " " + fuente,
                        resultado.oxigenos(), on[0], resultado.nitrogenos(), on[1], exacta ? "OK" : "<<", leidas);
                guardarAnotada(imagen, resultado, salida.resolve(clave + "__" + fuente + ".png"));
            }
        }

        System.out.println();
        System.out.println("Cuenta de O y N exacta, por procedencia:");
        int exactas = 0;
        int total = 0;
        for (Map.Entry<String, int[]> e : porFuente.entrySet()) {
            System.out.printf("  %-16s %3d de %3d%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
            exactas += e.getValue()[0];
            total += e.getValue()[1];
        }
        System.out.printf("  %-16s %3d de %3d%n", "TOTAL", exactas, total);

        System.out.println();
        System.out.println("Letra a letra en PubChem (la respuesta la da el color):");
        porColor.forEach((letra, c) -> System.out.printf("  %-2s %4d de %4d bien leídas%n", letra, c[0], c[1]));
        if (!confusiones.isEmpty()) {
            System.out.println("  Errores: " + confusiones);
        }
        System.out.println("Imágenes anotadas en " + salida.toAbsolutePath().normalize());
    }

    /** En PubChem el color dice qué letra es: O roja, N azul, H gris. Las negras pueden ser C u otras: null. */
    private static String letraPorColor(LetraLeida l) {
        var m = l.mancha();
        if (ProbarLocalizador.esRoja(m)) {
            return "O";
        }
        if (ProbarLocalizador.esAzul(m)) {
            return "N";
        }
        boolean gris = Math.max(m.rojo(), Math.max(m.verde(), m.azul())) - Math.min(m.rojo(), Math.min(m.verde(), m.azul())) < 40;
        return gris && m.rojo() > 90 ? "H" : null;
    }

    private static void guardarAnotada(BufferedImage imagen, Resultado resultado, Path destino) throws Exception {
        BufferedImage anotada = new BufferedImage(imagen.getWidth(), imagen.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = anotada.createGraphics();
        g.drawImage(imagen, 0, 0, null);
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font("SansSerif", Font.BOLD, Math.max(9, imagen.getHeight() / 30)));
        for (LetraLeida l : resultado.letras()) {
            var m = l.mancha();
            g.setColor(l.letra().equals("O") ? Color.MAGENTA : l.letra().equals("N") ? Color.CYAN.darker() : new Color(0, 150, 0));
            g.drawRect(m.x() - 1, m.y() - 1, m.ancho() + 1, m.alto() + 1);
            g.drawString(l.letra() + (l.multiplicador() > 1 ? String.valueOf(l.multiplicador()) : ""), m.x(), m.y() - 2);
        }
        g.dispose();
        ImageIO.write(anotada, "png", destino.toFile());
    }
}
