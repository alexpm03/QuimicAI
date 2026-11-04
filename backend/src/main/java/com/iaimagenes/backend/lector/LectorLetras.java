package com.iaimagenes.backend.lector;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.nn.Blocks;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.convolutional.Conv2d;
import ai.djl.nn.core.Linear;
import ai.djl.nn.norm.BatchNorm;
import ai.djl.nn.pooling.Pool;
import ai.djl.translate.NoopTranslator;

import com.iaimagenes.backend.lector.LocalizadorLetras.Mancha;

/**
 * El lector de etiquetas completo: localiza las letras (paso 1), separa las pegadas, lee cada una con
 * la mini red (paso 2) y junta cada número con su letra para contar O y N (paso 3).
 */
public class LectorLetras implements AutoCloseable {

    static final String NOMBRE = "lector-letras";

    /** Lo que dice la mini red de un recorte. */
    public record Lectura(String clase, double probabilidad) {}

    /** Una letra leída en la imagen; el multiplicador es su subíndice (el 3 de CH3), o 1 si no tiene. */
    public record LetraLeida(Mancha mancha, String letra, double probabilidad, int multiplicador) {}

    public record Resultado(List<LetraLeida> letras, int oxigenos, int nitrogenos) {}

    // Un número es subíndice de una letra si empieza, como mucho, a esta fracción del ancho de una letra
    // a su derecha, y su parte de abajo queda por debajo de la mitad de la letra
    static final double DISTANCIA_SUBINDICE = 0.8;

    private final Model model;
    private final Predictor<NDList, NDList> predictor;
    private final List<String> clases;

    public LectorLetras(Path carpeta) throws Exception {
        clases = Files.readAllLines(carpeta.resolve("synset.txt"), StandardCharsets.UTF_8);
        model = Model.newInstance(NOMBRE);
        model.setBlock(red(clases.size()));
        model.load(carpeta, NOMBRE);
        predictor = model.newPredictor(new NoopTranslator());
    }

    /** 24 -> 12 -> 6 píxeles, y una capa final con una salida por clase. */
    static SequentialBlock red(int numClases) {
        return new SequentialBlock()
                .add(bloque(16))
                .add(bloque(32))
                .add(Blocks.batchFlattenBlock())
                .add(Linear.builder().setUnits(64).build())
                .add(Activation.reluBlock())
                .add(Linear.builder().setUnits(numClases).build());
    }

    private static SequentialBlock bloque(int filtros) {
        return new SequentialBlock()
                .add(Conv2d.builder().setKernelShape(new Shape(3, 3)).optPadding(new Shape(1, 1)).setFilters(filtros).build())
                .add(BatchNorm.builder().build())
                .add(Activation.reluBlock())
                .add(Pool.maxPool2dBlock(new Shape(2, 2), new Shape(2, 2)));
    }

    /** Lee varios recortes de golpe. */
    public List<Lectura> leer(List<float[]> glifos) throws Exception {
        if (glifos.isEmpty()) {
            return List.of();
        }
        int area = Glifos.LADO * Glifos.LADO;
        // Las imágenes del dataset se guardan en RGB con los tres canales iguales: aquí se repite el gris 3 veces
        float[] lote = new float[glifos.size() * 3 * area];
        for (int i = 0; i < glifos.size(); i++) {
            for (int canal = 0; canal < 3; canal++) {
                System.arraycopy(glifos.get(i), 0, lote, (i * 3 + canal) * area, area);
            }
        }
        List<Lectura> lecturas = new ArrayList<>();
        try (NDManager manager = NDManager.newBaseManager()) {
            NDArray entrada = manager.create(lote, new Shape(glifos.size(), 3, Glifos.LADO, Glifos.LADO));
            NDArray probabilidades = predictor.predict(new NDList(entrada)).singletonOrThrow().softmax(1);
            float[] valores = probabilidades.toFloatArray();
            for (int i = 0; i < glifos.size(); i++) {
                int mejor = 0;
                for (int c = 1; c < clases.size(); c++) {
                    if (valores[i * clases.size() + c] > valores[i * clases.size() + mejor]) {
                        mejor = c;
                    }
                }
                lecturas.add(new Lectura(clases.get(mejor), valores[i * clases.size() + mejor]));
            }
        }
        return lecturas;
    }

    public Resultado leerImagen(BufferedImage imagen) throws Exception {
        List<Mancha> manchas = Glifos.separarPegadas(LocalizadorLetras.encontrar(imagen), imagen);
        List<float[]> glifos = manchas.stream().map(m -> Glifos.normalizar(imagen, m)).toList();
        List<Lectura> lecturas = leer(glifos);

        List<LetraLeida> letras = new ArrayList<>();
        for (int i = 0; i < manchas.size(); i++) {
            Mancha m = manchas.get(i);
            String porColor = letraDeBloque(m);
            Lectura lectura = porColor != null ? new Lectura(porColor, 1) : lecturas.get(i);
            if (!lectura.clase().equals("otro")) {
                letras.add(new LetraLeida(m, lectura.clase(), lectura.probabilidad(), 1));
            }
        }
        letras = juntarSubindices(letras);

        int oxigenos = 0;
        int nitrogenos = 0;
        for (LetraLeida l : letras) {
            if (l.letra().equals("O")) {
                oxigenos += l.multiplicador();
            } else if (l.letra().equals("N")) {
                nitrogenos += l.multiplicador();
            }
        }
        return new Resultado(letras, oxigenos, nitrogenos);
    }

    /**
     * En las imágenes muy pequeñas (PubChem a 100 px) los átomos no se dibujan como letras, sino como un
     * cuadradito macizo de su color: rojo el oxígeno, azul el nitrógeno, gris el hidrógeno. Ahí no hay
     * forma que leer y la única pista es el color. Devuelve la letra si la mancha es uno de esos bloques,
     * o null si es una letra normal que debe leer la mini red.
     */
    static String letraDeBloque(Mancha m) {
        boolean diminuta = m.alto() <= ALTURA_BLOQUE;
        boolean maciza = (double) m.pixeles() / (m.ancho() * m.alto()) >= DENSIDAD_BLOQUE;
        if (!diminuta || !maciza) {
            return null;
        }
        int rgb = (m.rojo() << 16) | (m.verde() << 8) | m.azul();
        int brillo = (m.rojo() + m.verde() + m.azul()) / 3;
        return switch (LocalizadorLetras.familiaDeColor(rgb)) {
            case 2 -> "O";
            case 3 -> "N";
            case 1 -> brillo >= 60 ? "H" : "otro";
            default -> "otro";
        };
    }

    // Un "bloque de color" mide como mucho 8 px de alto y es tinta en al menos el 70 % de su rectángulo
    // (una O o una N de verdad, aunque sean pequeñas, son anillos y trazos: mucho menos macizas)
    static final int ALTURA_BLOQUE = 8;
    static final double DENSIDAD_BLOQUE = 0.7;

    /** Cada número se pega a la letra que tiene justo a su izquierda (H3, H2...) y deja de contar como letra. */
    static List<LetraLeida> juntarSubindices(List<LetraLeida> leidas) {
        List<LetraLeida> letras = new ArrayList<>(leidas.stream().filter(l -> !esNumero(l.letra())).toList());
        if (letras.isEmpty()) {
            return letras;
        }
        int anchoLetra = letras.stream().mapToInt(l -> l.mancha().ancho()).sorted().toArray()[letras.size() / 2];
        for (LetraLeida numero : leidas.stream().filter(l -> esNumero(l.letra())).toList()) {
            Mancha n = numero.mancha();
            int mejor = -1;
            int menorHueco = Integer.MAX_VALUE;
            for (int i = 0; i < letras.size(); i++) {
                Mancha l = letras.get(i).mancha();
                int hueco = n.x() - (l.x() + l.ancho());
                boolean aLaDerecha = hueco >= -anchoLetra * 0.3 && hueco <= anchoLetra * DISTANCIA_SUBINDICE;
                boolean abajo = n.y() + n.alto() >= l.y() + l.alto() / 2 && n.y() >= l.y();
                if (aLaDerecha && abajo && hueco < menorHueco) {
                    mejor = i;
                    menorHueco = hueco;
                }
            }
            if (mejor >= 0) {
                LetraLeida l = letras.get(mejor);
                letras.set(mejor, new LetraLeida(l.mancha(), l.letra(), l.probabilidad(), Integer.parseInt(numero.letra())));
            }
        }
        return letras;
    }

    private static boolean esNumero(String clase) {
        return clase.chars().allMatch(Character::isDigit);
    }

    @Override
    public void close() {
        predictor.close();
        model.close();
    }
}
