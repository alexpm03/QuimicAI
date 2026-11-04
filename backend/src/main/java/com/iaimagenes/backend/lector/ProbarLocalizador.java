package com.iaimagenes.backend.lector;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import com.iaimagenes.backend.entrenamiento.Preprocesado;
import com.iaimagenes.backend.lector.LocalizadorLetras.Mancha;

/**
 * Prueba del paso 1 sobre el examen: dibuja un recuadro en cada letra encontrada y lo guarda en
 * pruebas/lector/, para revisarlo a ojo.
 *
 * Además, en las imágenes de PubChem (que pintan la O en rojo y la N en azul) cuenta las letras rojas
 * y azules y las compara con las O y N reales de la molécula: así se ve, sin leer todavía ninguna letra,
 * si el localizador las está encontrando todas.
 */
public class ProbarLocalizador {

    public static void main(String[] args) throws Exception {
        Path examen = Path.of("../pruebas/examen");
        Path salida = Path.of("../pruebas/lector");
        Files.createDirectories(salida);
        Map<String, int[]> esperadas = CombinadorLetras.letrasPorCompuesto(Path.of("../datos/compuestos_pubchem.csv"));

        int imagenesPubchem = 0;
        int cuentasExactas = 0;
        int letrasEsperadas = 0;
        int letrasEncontradas = 0;
        List<Path> imagenes;
        try (Stream<Path> rutas = Files.walk(examen, 2)) {
            imagenes = rutas.filter(r -> r.toString().endsWith(".png")).sorted().toList();
        }

        System.out.println();
        System.out.println("Imagen                            letras   (PubChem: O rojas / N azules, encontradas-esperadas)");
        for (Path ruta : imagenes) {
            String clave = ruta.getParent().getFileName().toString();
            String fuente = ruta.getFileName().toString().replace(".png", "");
            BufferedImage imagen = Preprocesado.aplanarSobreBlanco(ImageIO.read(ruta.toFile()));
            List<Mancha> letras = LocalizadorLetras.encontrar(imagen);

            int rojas = 0;
            int azules = 0;
            BufferedImage marcada = new BufferedImage(imagen.getWidth(), imagen.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D lienzo = marcada.createGraphics();
            lienzo.drawImage(imagen, 0, 0, null);
            lienzo.setStroke(new BasicStroke(1));
            for (Mancha m : letras) {
                boolean roja = esRoja(m);
                boolean azul = esAzul(m);
                rojas += roja ? 1 : 0;
                azules += azul ? 1 : 0;
                lienzo.setColor(roja ? Color.MAGENTA : azul ? Color.CYAN : new Color(0, 170, 0));
                lienzo.drawRect(m.x() - 1, m.y() - 1, m.ancho() + 1, m.alto() + 1);
            }
            lienzo.dispose();
            ImageIO.write(marcada, "png", salida.resolve(clave + "__" + fuente + ".png").toFile());

            String detalle = "";
            if (fuente.startsWith("pubchem")) {
                int[] on = esperadas.get(clave);
                boolean exacta = rojas == on[0] && azules == on[1];
                imagenesPubchem++;
                cuentasExactas += exacta ? 1 : 0;
                letrasEsperadas += on[0] + on[1];
                letrasEncontradas += Math.min(rojas, on[0]) + Math.min(azules, on[1]);
                detalle = String.format("O %d-%d  N %d-%d  %s", rojas, on[0], azules, on[1], exacta ? "OK" : "<<");
            }
            System.out.printf("%-33s %3d    %s%n", clave + " " + fuente, letras.size(), detalle);
        }

        System.out.println();
        System.out.printf("PubChem: cuenta de O y N exacta en %d de %d imágenes; %d de %d letras O/N encontradas.%n",
                cuentasExactas, imagenesPubchem, letrasEncontradas, letrasEsperadas);
        System.out.println("Recuadros en " + salida.toAbsolutePath().normalize()
                + "  (magenta = O roja, cian = N azul, verde = otra letra)");
    }

    // PubChem pinta el oxígeno en rojo (~255, 13, 13) y el nitrógeno en azul (~48, 80, 248);
    // el suavizado de los bordes aclara la media, por eso los márgenes son amplios
    static boolean esRoja(Mancha m) {
        return m.rojo() > m.verde() + 60 && m.rojo() > m.azul() + 60;
    }

    static boolean esAzul(Mancha m) {
        return m.azul() > m.rojo() + 60 && m.azul() > m.verde() + 40;
    }
}
