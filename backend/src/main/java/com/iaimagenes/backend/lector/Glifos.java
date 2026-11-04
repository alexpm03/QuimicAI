package com.iaimagenes.backend.lector;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import com.iaimagenes.backend.lector.LocalizadorLetras.Mancha;

/**
 * Preparación de cada letra antes de leerla:
 *
 *   - separar las letras pegadas: si una mancha mide de ancho lo que dos o tres letras ("OH", "HO", "CH"),
 *     se parte por las columnas con menos tinta
 *   - recortar y normalizar: cada letra se convierte en un cuadrado de LADO x LADO, tinta blanca sobre negro,
 *     sin importar su color, tamaño o fuente
 *
 * GenerarLetras usa el mismo recorte para el dataset, así la mini red ve las letras igual al entrenar y al leer.
 */
public final class Glifos {

    public static final int LADO = 24;

    // La letra ocupa como mucho este lado dentro del cuadrado; el resto es margen
    static final int CONTENIDO = 20;

    // Una mancha con este ancho respecto al de una letra normal se considera de varias letras pegadas
    static final double ANCHO_VARIAS_LETRAS = 1.6;

    // Solo se parten manchas con altura de letra mayúscula (los subíndices sueltos no se tocan)
    static final double ALTURA_PARA_PARTIR = 0.8;

    private Glifos() {}

    /**
     * Tinta de un píxel de 0 (blanco) a 1 (máxima). Se mira el canal más oscuro, así una O roja
     * (255, 0, 0) o una N azul cuentan tanto como una letra negra.
     */
    static float tinta(int rgb) {
        int masOscuro = Math.min((rgb >> 16) & 0xFF, Math.min((rgb >> 8) & 0xFF, rgb & 0xFF));
        return (255 - masOscuro) / 255f;
    }

    /** Recorta la zona, la escala sin deformarla para que su lado mayor mida CONTENIDO y la centra. */
    public static float[] normalizar(BufferedImage imagen, int x, int y, int ancho, int alto) {
        BufferedImage recorte = new BufferedImage(ancho, alto, BufferedImage.TYPE_BYTE_GRAY);
        float maximo = 1e-6f;
        for (int j = 0; j < alto; j++) {
            for (int i = 0; i < ancho; i++) {
                maximo = Math.max(maximo, tinta(imagen.getRGB(x + i, y + j)));
            }
        }
        for (int j = 0; j < alto; j++) {
            for (int i = 0; i < ancho; i++) {
                // Se estira para que el trazo más intenso valga 1: una letra gris clara y una negra quedan iguales
                int valor = Math.round(255 * tinta(imagen.getRGB(x + i, y + j)) / maximo);
                recorte.getRaster().setSample(i, j, 0, valor);
            }
        }

        double escala = (double) CONTENIDO / Math.max(ancho, alto);
        int nuevoAncho = Math.max(1, (int) Math.round(ancho * escala));
        int nuevoAlto = Math.max(1, (int) Math.round(alto * escala));
        Image escalada = recorte.getScaledInstance(nuevoAncho, nuevoAlto, Image.SCALE_AREA_AVERAGING);
        BufferedImage cuadrado = new BufferedImage(LADO, LADO, BufferedImage.TYPE_BYTE_GRAY);
        cuadrado.getGraphics().drawImage(escalada, (LADO - nuevoAncho) / 2, (LADO - nuevoAlto) / 2, null);

        float[] glifo = new float[LADO * LADO];
        for (int j = 0; j < LADO; j++) {
            for (int i = 0; i < LADO; i++) {
                glifo[j * LADO + i] = cuadrado.getRaster().getSample(i, j, 0) / 255f;
            }
        }
        return glifo;
    }

    public static float[] normalizar(BufferedImage imagen, Mancha m) {
        return normalizar(imagen, m.x(), m.y(), m.ancho(), m.alto());
    }

    /** El glifo como imagen en grises (para guardar el dataset y para ver qué lee la red). */
    public static BufferedImage comoImagen(float[] glifo) {
        BufferedImage imagen = new BufferedImage(LADO, LADO, BufferedImage.TYPE_INT_RGB);
        for (int j = 0; j < LADO; j++) {
            for (int i = 0; i < LADO; i++) {
                int v = Math.round(glifo[j * LADO + i] * 255);
                imagen.setRGB(i, j, (v << 16) | (v << 8) | v);
            }
        }
        return imagen;
    }

    /** Altura típica de las letras de la imagen: la de las más altas, para que los subíndices no la hundan. */
    static int alturaTipica(List<Mancha> letras) {
        int[] alturas = letras.stream().mapToInt(Mancha::alto).sorted().toArray();
        return alturas[(alturas.length * 3) / 4];
    }

    /**
     * Parte las manchas que son varias letras pegadas. El ancho de una letra normal se calcula con las
     * manchas de altura de mayúscula que no son más anchas que altas (una O, una N, una H sueltas).
     */
    public static List<Mancha> separarPegadas(List<Mancha> letras, BufferedImage imagen) {
        if (letras.isEmpty()) {
            return letras;
        }
        int alturaTipica = alturaTipica(letras);
        int[] anchos = letras.stream()
                .filter(m -> m.alto() >= ALTURA_PARA_PARTIR * alturaTipica && m.ancho() <= 1.2 * m.alto())
                .mapToInt(Mancha::ancho).sorted().toArray();
        if (anchos.length == 0) {
            return letras;
        }
        int anchoLetra = anchos[anchos.length / 2];

        List<Mancha> resultado = new ArrayList<>();
        for (Mancha m : letras) {
            boolean variasLetras = m.alto() >= ALTURA_PARA_PARTIR * alturaTipica
                    && m.ancho() > ANCHO_VARIAS_LETRAS * anchoLetra;
            if (!variasLetras) {
                resultado.add(m);
                continue;
            }
            int partes = Math.max(2, Math.min(4, (int) Math.round((double) m.ancho() / anchoLetra)));
            resultado.addAll(partir(m, partes, anchoLetra, imagen));
        }
        return resultado;
    }

    /** Corta la mancha en vertical por la columna con menos tinta cerca de cada corte esperado. */
    private static List<Mancha> partir(Mancha m, int partes, int anchoLetra, BufferedImage imagen) {
        float[] porColumna = new float[m.ancho()];
        for (int i = 0; i < m.ancho(); i++) {
            for (int j = 0; j < m.alto(); j++) {
                porColumna[i] += tinta(imagen.getRGB(m.x() + i, m.y() + j));
            }
        }
        List<Integer> cortes = new ArrayList<>();
        cortes.add(0);
        int margen = Math.max(1, anchoLetra / 3);
        for (int k = 1; k < partes; k++) {
            int esperado = k * m.ancho() / partes;
            int mejor = esperado;
            for (int c = Math.max(1, esperado - margen); c <= Math.min(m.ancho() - 2, esperado + margen); c++) {
                if (porColumna[c] < porColumna[mejor]) {
                    mejor = c;
                }
            }
            cortes.add(mejor);
        }
        cortes.add(m.ancho());

        List<Mancha> trozos = new ArrayList<>();
        for (int k = 0; k + 1 < cortes.size(); k++) {
            Mancha trozo = recortarATinta(imagen, m.x() + cortes.get(k), m.y(), cortes.get(k + 1) - cortes.get(k), m.alto());
            if (trozo != null) {
                trozos.add(trozo);
            }
        }
        return trozos;
    }

    /** Ajusta el rectángulo a la tinta que hay dentro y calcula su color medio. */
    private static Mancha recortarATinta(BufferedImage imagen, int x, int y, int ancho, int alto) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        int pixeles = 0;
        long rojo = 0, verde = 0, azul = 0;
        for (int j = 0; j < alto; j++) {
            for (int i = 0; i < ancho; i++) {
                int rgb = imagen.getRGB(x + i, y + j);
                if (tinta(rgb) * 255 > 255 - LocalizadorLetras.UMBRAL_TINTA) {
                    x0 = Math.min(x0, x + i);
                    y0 = Math.min(y0, y + j);
                    x1 = Math.max(x1, x + i);
                    y1 = Math.max(y1, y + j);
                    pixeles++;
                    rojo += (rgb >> 16) & 0xFF;
                    verde += (rgb >> 8) & 0xFF;
                    azul += rgb & 0xFF;
                }
            }
        }
        if (pixeles == 0) {
            return null;
        }
        return new Mancha(x0, y0, x1 - x0 + 1, y1 - y0 + 1, pixeles,
                (int) (rojo / pixeles), (int) (verde / pixeles), (int) (azul / pixeles));
    }
}
