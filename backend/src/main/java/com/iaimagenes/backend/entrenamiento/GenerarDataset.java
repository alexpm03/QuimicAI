package com.iaimagenes.backend.entrenamiento;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.openscience.cdk.depict.DepictionGenerator;
import org.openscience.cdk.geometry.GeometryUtil;
import org.openscience.cdk.interfaces.IAtomContainer;
import org.openscience.cdk.layout.StructureDiagramGenerator;
import org.openscience.cdk.renderer.generators.standard.StandardGenerator;
import org.openscience.cdk.silent.SilentChemObjectBuilder;
import org.openscience.cdk.smiles.SmilesParser;

import ai.djl.ndarray.NDManager;

public class GenerarDataset {

    record Compuesto(String clave, String smiles) {}

    private static final String[] FUENTES = {"Arial", "Helvetica", "Times New Roman", "Verdana", "Courier New"};

    private static final int LADO_MIN = 128;
    private static final int LADO_MAX = 384;

    // Una de cada tres imágenes se dibuja diminuta, para que la red vea letras pixeladas
    // como las de las imágenes pequeñas de PubChem (donde una "O" es apenas un cuadradito rojo)
    private static final double PROBABILIDAD_PEQUENA = 1.0 / 3;
    private static final int PEQUENA_MIN = 32;
    private static final int PEQUENA_MAX = 110;
    private static final int TRAIN_POR_CLASE = 500;
    private static final int TEST_POR_CLASE = 100;

    /**
     * Uso:  GenerarDataset          -> 64 x 64, para la red propia, en ../dataset
     *       GenerarDataset resnet   -> 128 x 128, para ResNet18, en ../dataset_128
     * Los dibujos son los mismos (misma semilla); solo cambia el tamaño final al que se guardan.
     */
    public static void main(String[] args) throws Exception {
        ConfigModelo red = List.of(args).contains("resnet") ? ConfigModelo.RESNET18 : ConfigModelo.PROPIA;
        Path compuestosCsv = Path.of("../datos/compuestos_pubchem.csv");
        Path salida = red.carpetaDataset();
        List<Compuesto> compuestos = leerCompuestos(compuestosCsv);
        System.out.println("Compuestos en " + compuestosCsv.getFileName() + ": " + compuestos.size()
                + ". Imágenes de " + red.tamano() + " px en " + salida);

        // El dataset se genera entero cada vez: borramos el anterior para que no queden
        // clases que ya no están en la lista ni imágenes sobrantes de otra ejecución
        borrarCarpeta(salida);

        Random random = new Random(42);
        SmilesParser parser = new SmilesParser(SilentChemObjectBuilder.getInstance());
        StructureDiagramGenerator sdg = new StructureDiagramGenerator();

        try (NDManager manager = NDManager.newBaseManager()) {
            for (Compuesto compuesto : compuestos) {
                IAtomContainer molecula = parser.parseSmiles(compuesto.smiles());
                sdg.generateCoordinates(molecula);

                generar(molecula, salida.resolve("train").resolve(compuesto.clave()), TRAIN_POR_CLASE, random, manager, red.tamano());
                generar(molecula, salida.resolve("test").resolve(compuesto.clave()), TEST_POR_CLASE, random, manager, red.tamano());
                System.out.println("Generado: " + compuesto.clave());
            }
        }
    }

    /** Lee datos/compuestos_pubchem.csv (clave;nombre;cid;formula;smiles), creado por DescargarCompuestos. */
    private static List<Compuesto> leerCompuestos(Path csv) throws Exception {
        if (!Files.exists(csv)) {
            throw new IllegalStateException("No existe " + csv.toAbsolutePath().normalize()
                    + ". Ejecuta antes DescargarCompuestos.");
        }
        return Files.readAllLines(csv, StandardCharsets.UTF_8).stream()
                .skip(1)
                .filter(linea -> !linea.isBlank())
                .map(linea -> linea.split(";"))
                .map(c -> new Compuesto(c[0].trim(), c[4].trim()))
                .toList();
    }

    private static void borrarCarpeta(Path carpeta) throws Exception {
        if (!Files.exists(carpeta)) {
            return;
        }
        System.out.println("Borrando el dataset anterior en " + carpeta.toAbsolutePath().normalize());
        try (Stream<Path> rutas = Files.walk(carpeta)) {
            // Primero los archivos y después las carpetas que los contienen
            for (Path ruta : rutas.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(ruta);
            }
        }
    }

    private static void generar(IAtomContainer molecula, Path carpeta, int cantidad, Random random,
            NDManager manager, int tamano) throws Exception {
        Files.createDirectories(carpeta);
        for (int i = 0; i < cantidad; i++) {
            BufferedImage dibujo = conFondo(dibujar(molecula, random), random);
            BufferedImage lista = Preprocesado.vistaDeLaRed(dibujo, manager, tamano);
            ImageIO.write(lista, "png", carpeta.resolve(String.format("%04d.png", i)).toFile());
        }
    }


    /** Dibuja la molécula con un estilo aleatorio: giro, tamaño, proporción, grosor, fuente, colores, CH3. */
    private static BufferedImage dibujar(IAtomContainer molecula, Random random) throws Exception {
        // Gira la molécula un ángulo aleatorio sobre su centro
        double angulo = random.nextDouble() * 2 * Math.PI;
        GeometryUtil.rotate(molecula, GeometryUtil.get2DCenter(molecula), angulo);

        Font fuente = new Font(FUENTES[random.nextInt(FUENTES.length)], Font.PLAIN, 10 + random.nextInt(9));
        boolean pequena = random.nextDouble() < PROBABILIDAD_PEQUENA;
        int minimo = pequena ? PEQUENA_MIN : LADO_MIN;
        int maximo = pequena ? PEQUENA_MAX : LADO_MAX;
        int ancho = minimo + random.nextInt(maximo - minimo + 1);
        int alto = minimo + random.nextInt(maximo - minimo + 1);

        DepictionGenerator generador = new DepictionGenerator(fuente)
                .withSize(ancho, alto)
                .withFillToFit()
                // En las pequeñas, un margen de 30 px se comería el dibujo
                .withMargin(random.nextInt(pequena ? 6 : 31))
                // En las pequeñas, un trazo fino sale gris claro y el umbral de limpieza lo borraría
                .withParam(StandardGenerator.StrokeRatio.class,
                        pequena ? 1.6 + random.nextDouble() * 1.4 : 0.6 + random.nextDouble() * 1.4);

        if (random.nextBoolean()) {
            generador = generador.withAtomColors();
        }
        if (random.nextBoolean()) {
            // Escribe los metilos como "CH3" en vez de dejar solo la punta de la línea
            generador = generador.withTerminalCarbons();
        }

        return generador.depict(molecula).toImg();
    }

    /** Pone el dibujo sobre un fondo aleatorio: blanco, gris claro, cuadros o blanco con ruido. */
    private static BufferedImage conFondo(BufferedImage dibujo, Random random) {
        int ancho = dibujo.getWidth();
        int alto = dibujo.getHeight();
        int tipo = random.nextInt(4);
        int gris = 215 + random.nextInt(36);
        int celda = 8 + random.nextInt(17);
        double ruido = tipo == 3 ? 5 + random.nextDouble() * 10 : 0;

        BufferedImage resultado = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                int fondo = switch (tipo) {
                    case 1 -> gris;
                    case 2 -> ((x / celda + y / celda) % 2 == 0) ? 255 : gris;
                    default -> 255;
                };
                double variacion = ruido > 0 ? random.nextGaussian() * ruido : 0;
                int rgb = dibujo.getRGB(x, y);

                // Mezcla tipo "multiplicar": el blanco del dibujo deja ver el fondo, las líneas oscuras se quedan
                int r = mezclar((rgb >> 16) & 0xFF, fondo, variacion);
                int g = mezclar((rgb >> 8) & 0xFF, fondo, variacion);
                int b = mezclar(rgb & 0xFF, fondo, variacion);
                resultado.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return resultado;
    }

    private static int mezclar(int dibujo, int fondo, double variacion) {
        int valor = (int) Math.round(dibujo * fondo / 255.0 + variacion);
        return Math.max(0, Math.min(255, valor));
    }
}
