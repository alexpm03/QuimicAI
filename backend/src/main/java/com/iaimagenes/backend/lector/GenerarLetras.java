package com.iaimagenes.backend.lector;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

/**
 * Dataset para la mini red que lee letras, en ../dataset_letras/{train,test}/<clase>/.
 *
 * Cada imagen es una letra dibujada con Java en una fuente, tamaño, grosor, color y giro al azar,
 * recortada y normalizada con {@link Glifos} igual que se recortan las letras reales.
 * Para que se parezca a lo que sale de las imágenes reales se le añade a veces:
 *   - tamaño diminuto (7-12 px), como las letras de las imágenes pequeñas de PubChem
 *   - otra letra pegada al lado, como los trozos que quedan al partir "OH" o "CH"
 *   - el extremo de un enlace asomando por una esquina
 *   - un poco de desenfoque
 * La clase "otro" son cosas que el localizador puede confundir con letras: cuñas, guiones, trozos de enlace.
 */
public class GenerarLetras {

    static final List<String> CLASES = List.of("O", "N", "H", "C", "2", "3", "otro");

    private static final int TRAIN_POR_CLASE = 1500;
    private static final int TEST_POR_CLASE = 300;

    private static final List<String> FUENTES_DESEADAS = List.of("Arial", "Helvetica", "Times New Roman", "Verdana",
            "Courier New", "Calibri", "Segoe UI", "Tahoma", "Georgia", "Cambria", "Consolas", "DejaVu Sans",
            "Liberation Sans", "SansSerif", "Serif");

    private static final String VECINAS = "OHCN23";

    public static void main(String[] args) throws Exception {
        Path salida = Path.of("../dataset_letras");
        borrarCarpeta(salida);
        Set<String> instaladas = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        List<String> fuentes = FUENTES_DESEADAS.stream().filter(instaladas::contains).toList();
        System.out.println("Fuentes: " + fuentes);

        Random random = new Random(7);
        for (String clase : CLASES) {
            generar(clase, salida.resolve("train").resolve(clase), TRAIN_POR_CLASE, fuentes, random);
            generar(clase, salida.resolve("test").resolve(clase), TEST_POR_CLASE, fuentes, random);
            System.out.println("Generada la clase " + clase);
        }
        System.out.println("Dataset de letras en " + salida.toAbsolutePath().normalize());
    }

    private static void generar(String clase, Path carpeta, int cantidad, List<String> fuentes, Random random)
            throws Exception {
        Files.createDirectories(carpeta);
        for (int i = 0; i < cantidad; i++) {
            float[] glifo = clase.equals("otro") ? otro(random) : letra(clase, fuentes, random);
            ImageIO.write(Glifos.comoImagen(glifo), "png", carpeta.resolve(String.format("%04d.png", i)).toFile());
        }
    }

    private static float[] letra(String letra, List<String> fuentes, Random random) {
        boolean diminuta = random.nextDouble() < 0.4;
        int tamano = diminuta ? 7 + random.nextInt(6) : 14 + random.nextInt(47);
        Font fuente = new Font(fuentes.get(random.nextInt(fuentes.size())),
                random.nextDouble() < 0.25 ? Font.BOLD : Font.PLAIN, tamano);
        Color color = colorAlAzar(random);
        double giro = Math.toRadians(random.nextGaussian() * 3);
        boolean suavizado = random.nextDouble() < 0.85;
        int baseX = 60;
        int baseY = 100;

        // Primero la letra sola, para saber su rectángulo exacto (lo que encontraría el localizador)
        BufferedImage sola = lienzo();
        Graphics2D g = preparar(sola, suavizado);
        g.rotate(giro, baseX, baseY);
        g.setFont(fuente);
        g.setColor(color);
        g.drawString(letra, baseX, baseY);
        FontMetrics medidas = g.getFontMetrics();
        g.dispose();
        int[] caja = cajaDeTinta(sola);
        if (caja == null) {
            return letra(letra, fuentes, random);
        }

        // Después la escena completa: la letra, quizá una vecina pegada y quizá un enlace asomando
        BufferedImage escena = lienzo();
        g = preparar(escena, suavizado);
        g.rotate(giro, baseX, baseY);
        g.setFont(fuente);
        g.setColor(color);
        g.drawString(letra, baseX, baseY);
        if (random.nextDouble() < 0.35) {
            int hueco = random.nextInt(3) - 1;
            String vecina = String.valueOf(VECINAS.charAt(random.nextInt(VECINAS.length())));
            g.drawString(vecina, baseX + medidas.stringWidth(letra) + hueco, baseY);
        }
        g.setTransform(new java.awt.geom.AffineTransform());
        if (random.nextDouble() < 0.4) {
            enlaceEnEsquina(g, caja, tamano, random);
        }
        g.dispose();
        if (random.nextDouble() < 0.25) {
            escena = desenfocar(escena);
        }

        int extra = random.nextInt(2);
        int x = Math.max(0, caja[0] - extra);
        int y = Math.max(0, caja[1] - extra);
        int ancho = Math.min(escena.getWidth() - x, caja[2] + 2 * extra);
        int alto = Math.min(escena.getHeight() - y, caja[3] + 2 * extra);
        return Glifos.normalizar(escena, x, y, ancho, alto);
    }

    /** Formas que no son letras pero que el localizador puede tomar por una. */
    private static float[] otro(Random random) {
        BufferedImage imagen = lienzo();
        Graphics2D g = preparar(imagen, random.nextDouble() < 0.85);
        g.setColor(colorAlAzar(random));
        double escala = random.nextDouble() < 0.4 ? 0.2 + random.nextDouble() * 0.2 : 0.5 + random.nextDouble();
        int cx = 100;
        int cy = 75;
        double angulo = random.nextDouble() * Math.PI * 2;
        g.rotate(angulo, cx, cy);
        switch (random.nextInt(5)) {
            case 0 -> { // cuña maciza
                int largo = (int) ((20 + random.nextInt(30)) * escala) + 3;
                int ancho = Math.max(2, (int) ((5 + random.nextInt(10)) * escala));
                g.fillPolygon(new Polygon(new int[] {cx - largo / 2, cx + largo / 2, cx + largo / 2},
                        new int[] {cy, cy - ancho / 2, cy + ancho / 2}, 3));
            }
            case 1 -> { // cuña de guiones
                int guiones = 3 + random.nextInt(5);
                float grosor = (float) Math.max(1, 2 * escala);
                g.setStroke(new BasicStroke(grosor));
                for (int k = 0; k < guiones; k++) {
                    int x = cx + (int) (k * 5 * escala);
                    int medio = (int) ((2 + k * 1.5) * escala) + 1;
                    g.drawLine(x, cy - medio, x, cy + medio);
                }
            }
            case 2 -> { // trozo corto y grueso de enlace
                float grosor = (float) Math.max(2, (2 + random.nextInt(5)) * escala);
                g.setStroke(new BasicStroke(grosor));
                int largo = (int) (grosor * (1 + random.nextDouble() * 2));
                g.drawLine(cx, cy, cx + largo, cy);
            }
            case 3 -> { // esquina de enlaces (dos trozos que se juntan)
                float grosor = (float) Math.max(1, (1 + random.nextInt(3)) * escala);
                g.setStroke(new BasicStroke(grosor));
                int largo = (int) ((8 + random.nextInt(12)) * escala) + 2;
                g.drawLine(cx, cy, cx + largo, cy);
                g.drawLine(cx, cy, cx + largo / 2, cy + (int) (largo * 0.87));
            }
            default -> { // enlace doble corto
                float grosor = (float) Math.max(1, (1 + random.nextInt(2)) * escala);
                g.setStroke(new BasicStroke(grosor));
                int largo = (int) ((6 + random.nextInt(10)) * escala) + 2;
                int separacion = (int) Math.max(2, 4 * escala);
                g.drawLine(cx, cy, cx + largo, cy);
                g.drawLine(cx, cy + separacion, cx + largo, cy + separacion);
            }
        }
        g.dispose();
        int[] caja = cajaDeTinta(imagen);
        if (caja == null) {
            return otro(random);
        }
        return Glifos.normalizar(imagen, caja[0], caja[1], caja[2], caja[3]);
    }

    /** El extremo de una línea que entra un poco por una esquina del rectángulo de la letra. */
    private static void enlaceEnEsquina(Graphics2D g, int[] caja, int tamano, Random random) {
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(Math.max(1f, tamano / 14f)));
        int x = random.nextBoolean() ? caja[0] : caja[0] + caja[2];
        int y = random.nextBoolean() ? caja[1] : caja[1] + caja[3];
        int dentroX = x == caja[0] ? random.nextInt(3) : -random.nextInt(3);
        int dentroY = y == caja[1] ? random.nextInt(3) : -random.nextInt(3);
        int fueraX = x == caja[0] ? -tamano : tamano;
        int fueraY = y == caja[1] ? -tamano : tamano;
        g.drawLine(x + dentroX, y + dentroY, x + fueraX, y + fueraY);
    }

    private static Color colorAlAzar(Random random) {
        return switch (random.nextInt(6)) {
            case 0 -> new Color(220 + random.nextInt(36), random.nextInt(40), random.nextInt(40));   // rojo, como las O
            case 1 -> new Color(30 + random.nextInt(30), 60 + random.nextInt(40), 220 + random.nextInt(36)); // azul, como las N
            case 2 -> {
                int gris = 110 + random.nextInt(50);  // gris, como las H de PubChem
                yield new Color(gris, gris + 10, gris + 15);
            }
            default -> {
                int gris = random.nextInt(60);
                yield new Color(gris, gris, gris);
            }
        };
    }

    private static BufferedImage lienzo() {
        BufferedImage imagen = new BufferedImage(220, 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = imagen.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, imagen.getWidth(), imagen.getHeight());
        g.dispose();
        return imagen;
    }

    private static Graphics2D preparar(BufferedImage imagen, boolean suavizado) {
        Graphics2D g = imagen.createGraphics();
        Object valor = suavizado ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, valor);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                suavizado ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        return g;
    }

    /** Rectángulo {x, y, ancho, alto} de la tinta que el localizador contaría, o null si no hay. */
    private static int[] cajaDeTinta(BufferedImage imagen) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < imagen.getHeight(); y++) {
            for (int x = 0; x < imagen.getWidth(); x++) {
                if (Glifos.tinta(imagen.getRGB(x, y)) * 255 > 255 - LocalizadorLetras.UMBRAL_TINTA) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
        }
        return x1 < 0 ? null : new int[] {x0, y0, x1 - x0 + 1, y1 - y0 + 1};
    }

    /** Media de cada píxel con sus 8 vecinos. */
    private static BufferedImage desenfocar(BufferedImage imagen) {
        BufferedImage borrosa = new BufferedImage(imagen.getWidth(), imagen.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < imagen.getHeight(); y++) {
            for (int x = 0; x < imagen.getWidth(); x++) {
                int[] suma = new int[3];
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if (nx >= 0 && ny >= 0 && nx < imagen.getWidth() && ny < imagen.getHeight()) {
                            int rgb = imagen.getRGB(nx, ny);
                            suma[0] += (rgb >> 16) & 0xFF;
                            suma[1] += (rgb >> 8) & 0xFF;
                            suma[2] += rgb & 0xFF;
                            n++;
                        }
                    }
                }
                borrosa.setRGB(x, y, ((suma[0] / n) << 16) | ((suma[1] / n) << 8) | (suma[2] / n));
            }
        }
        return borrosa;
    }

    private static void borrarCarpeta(Path carpeta) throws Exception {
        if (!Files.exists(carpeta)) {
            return;
        }
        try (Stream<Path> rutas = Files.walk(carpeta)) {
            for (Path ruta : rutas.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(ruta);
            }
        }
    }
}
