package com.iaimagenes.backend.entrenamiento;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Transform;

/**
 * Preparación de la imagen antes de entrar en la red. Entra la imagen tal cual
 * (alto x ancho x 3 colores, valores 0-255) y pasa por estos pasos:
 *
 *   1. invertir y limpiar: fondo = 0, trazos = 1, y se borran grises claros, cuadros y ruido
 *   2. recortar al dibujo: fuera los bordes vacíos, ocupe la molécula lo que ocupe en la imagen
 *   3. cuadrar: se rellena con fondo hasta hacerla cuadrada, con un pequeño margen
 *   4. reducir al tamaño de la red (64 x 64 la propia, 128 x 128 ResNet18)
 *   5. normalizar: el trazo más intenso vale 1
 *
 * GenerarDataset aplica {@link #preparar(int)} una sola vez y guarda el resultado ya reducido;
 * el backend aplica {@link #crear(ConfigModelo)}, que es lo mismo más lo que pide cada red.
 */
public final class Preprocesado {

    // Medias y desviaciones de los colores de ImageNet: las redes preentrenadas esperan las imágenes así
    private static final float[] MEDIA_IMAGENET = {0.485f, 0.456f, 0.406f};
    private static final float[] DESVIACION_IMAGENET = {0.229f, 0.224f, 0.225f};

    // Tras invertir, todo lo que no llegue a este nivel se considera fondo (gris claro, cuadros, ruido)
    private static final float UMBRAL = 0.35f;

    // Margen alrededor del dibujo tras recortar, en proporción a su lado mayor
    private static final double MARGEN = 0.06;

    private Preprocesado() {}

    /** Para predecir: pasos 1 a 5 y después el tensor 3 x tamaño x tamaño que espera la red. */
    public static Pipeline crear(ConfigModelo config) {
        return preparar(config.tamano())
                .add(imagen -> imagen.transpose(2, 0, 1))
                .add(paraLaRed(config));
    }

    /**
     * Lo que se hace a un tensor 3 x tamaño x tamaño (0 a 1) justo antes de entrar en la red.
     * La propia no necesita nada más; las preentrenadas, la normalización de ImageNet.
     */
    public static Transform paraLaRed(ConfigModelo config) {
        return config.preentrenada() ? new Normalize(MEDIA_IMAGENET, DESVIACION_IMAGENET) : imagen -> imagen;
    }

    /** Pasos 1 a 5: devuelve la imagen preparada en tamaño x tamaño x 3, con valores de 0 a 1. */
    public static Pipeline preparar(int tamano) {
        return new Pipeline()
                .add(Preprocesado::invertirYLimpiar)
                .add(Preprocesado::recortarAlDibujo)
                .add(Preprocesado::cuadrar)
                // Promedio de cada zona (AREA). Probamos a reducir por máximo para salvar las líneas finas
                // de PubChem, pero engordaba letras y dobles enlaces y bajó el examen de 23 a 19 de 30.
                .add(new Resize(tamano, tamano, Image.Interpolation.AREA))
                .add(Preprocesado::normalizar);
    }

    /**
     * Los PNG transparentes guardan el fondo como negro invisible: lo pintamos de blanco,
     * igual que hace el navegador, para que la red vea lo mismo que vemos nosotros.
     */
    public static BufferedImage aplanarSobreBlanco(BufferedImage original) {
        BufferedImage aplanada = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D lienzo = aplanada.createGraphics();
        lienzo.setColor(Color.WHITE);
        lienzo.fillRect(0, 0, original.getWidth(), original.getHeight());
        lienzo.drawImage(original, 0, 0, null);
        lienzo.dispose();
        return aplanada;
    }

    /**
     * Aplica los pasos 1 a 5 y devuelve el resultado como imagen de tamaño x tamaño: exactamente lo que ve la red.
     * Se ve "en negativo" (fondo negro, trazos claros).
     */
    public static BufferedImage vistaDeLaRed(BufferedImage dibujo, NDManager manager, int tamano) {
        try (NDManager temporal = manager.newSubManager()) {
            NDArray pixeles = ImageFactory.getInstance().fromImage(dibujo).toNDArray(temporal, Image.Flag.COLOR);
            // alto x ancho x 3, con valores de 0 a 1
            float[] valores = preparar(tamano).transform(new NDList(pixeles)).singletonOrThrow().toFloatArray();

            BufferedImage vista = new BufferedImage(tamano, tamano, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < tamano; y++) {
                for (int x = 0; x < tamano; x++) {
                    int i = (y * tamano + x) * 3;
                    int r = Math.round(valores[i] * 255);
                    int g = Math.round(valores[i + 1] * 255);
                    int b = Math.round(valores[i + 2] * 255);
                    vista.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            return vista;
        }
    }

    /** Pasa a 0-1, invierte (fondo = 0, trazos = 1) y borra todo lo que sea fondo claro. */
    static NDArray invertirYLimpiar(NDArray imagen) {
        NDArray invertida = imagen.toType(DataType.FLOAT32, false).div(255f).neg().add(1f);

        // Un píxel es trazo si alguno de sus 3 colores supera el umbral (así se conservan O rojos, N azules...)
        NDArray esTrazo = invertida.max(new int[] {2}, true).gt(UMBRAL).toType(DataType.FLOAT32, false);
        return invertida.mul(esTrazo);
    }

    /** Se queda solo con el rectángulo que contiene trazos: la molécula pasa a ocupar toda la imagen. */
    static NDArray recortarAlDibujo(NDArray imagen) {
        NDArray hayTrazo = imagen.max(new int[] {2}).gt(0f).toType(DataType.FLOAT32, false);
        float[] porFila = hayTrazo.sum(new int[] {1}).toFloatArray();
        float[] porColumna = hayTrazo.sum(new int[] {0}).toFloatArray();

        int arriba = primeroConTrazo(porFila);
        if (arriba < 0) {
            // Imagen vacía: no hay nada que recortar
            return imagen;
        }
        int abajo = ultimoConTrazo(porFila);
        int izquierda = primeroConTrazo(porColumna);
        int derecha = ultimoConTrazo(porColumna);
        return imagen.get(new NDIndex("{}:{}, {}:{}", arriba, abajo + 1, izquierda, derecha + 1));
    }

    /** Rellena con fondo (0) por los lados hasta hacerla cuadrada, dejando un margen alrededor. */
    static NDArray cuadrar(NDArray imagen) {
        long alto = imagen.getShape().get(0);
        long ancho = imagen.getShape().get(1);
        long lado = Math.max(alto, ancho);
        lado += 2 * Math.round(lado * MARGEN);

        NDArray conFilas = rellenar(imagen, 0, lado - alto);
        return rellenar(conFilas, 1, lado - ancho);
    }

    /** Escala para que el trazo más intenso valga 1: una línea fina y una gruesa llegan igual de fuertes. */
    static NDArray normalizar(NDArray imagen) {
        return imagen.div(imagen.max().add(1e-6f));
    }

    private static NDArray rellenar(NDArray imagen, int eje, long falta) {
        if (falta <= 0) {
            return imagen;
        }
        long antes = falta / 2;
        long despues = falta - antes;

        NDList trozos = new NDList();
        if (antes > 0) {
            trozos.add(fondo(imagen, eje, antes));
        }
        trozos.add(imagen);
        trozos.add(fondo(imagen, eje, despues));
        return NDArrays.concat(trozos, eje);
    }

    private static NDArray fondo(NDArray imagen, int eje, long grosor) {
        Shape forma = imagen.getShape();
        Shape bloque = eje == 0
                ? new Shape(grosor, forma.get(1), forma.get(2))
                : new Shape(forma.get(0), grosor, forma.get(2));
        return imagen.getManager().zeros(bloque, imagen.getDataType());
    }

    private static int primeroConTrazo(float[] valores) {
        for (int i = 0; i < valores.length; i++) {
            if (valores[i] > 0) {
                return i;
            }
        }
        return -1;
    }

    private static int ultimoConTrazo(float[] valores) {
        for (int i = valores.length - 1; i >= 0; i--) {
            if (valores[i] > 0) {
                return i;
            }
        }
        return -1;
    }
}
