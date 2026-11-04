package com.iaimagenes.backend.lector;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Paso 1 del lector de etiquetas: encuentra en la imagen las manchas que son letras (O, N, H, C, 2, 3...).
 * No usa ninguna red neuronal, solo procesado de imagen:
 *
 *   1. pasar a blanco y negro: un píxel es tinta si su canal más oscuro baja de UMBRAL_TINTA
 *   2. separar manchas: cada grupo de píxeles de tinta que se tocan (también en diagonal) es una mancha
 *   3. quedarse con las que parecen letras:
 *      - no son una línea: una línea recta tiene todos sus píxeles a lo largo de una dirección
 *      - no son el esqueleto: la mancha de las líneas de la molécula ocupa buena parte del dibujo
 *      - tienen la altura de una letra: parecida a la del resto de letras de la imagen
 *
 * Funciona porque en las fórmulas químicas siempre se deja un hueco entre cada letra y su enlace.
 */
public final class LocalizadorLetras {

    /** Una mancha que parece letra: su rectángulo, cuántos píxeles tiene y su color medio. */
    public record Mancha(int x, int y, int ancho, int alto, int pixeles, int rojo, int verde, int azul) {}

    // Más claro que el umbral del preprocesado (166): en las imágenes pequeñas las letras son grises
    // por el suavizado y con 166 se partirían en trozos
    static final int UMBRAL_TINTA = 190;

    // Manchas con menos píxeles que esto son ruido o puntos sueltos
    static final int PIXELES_MINIMOS = 6;

    // Una mancha es "línea" si se estira en una dirección este número de veces más que en la otra
    // (cociente entre la dispersión a lo largo y a lo ancho). Una O, H o N rondan 1-3; una línea, 20 o más.
    static final double ALARGAMIENTO_LINEA = 5;

    // Una letra nunca ocupa más de este porcentaje del lado mayor del dibujo
    static final double PROPORCION_MAXIMA = 0.3;

    // Alturas aceptadas respecto a la altura típica de las letras de la imagen
    // (los subíndices de CH3 o NH2 miden en torno al 60 %; los guiones de las cuñas, bastante menos)
    static final double ALTURA_MINIMA = 0.55;
    static final double ALTURA_MAXIMA = 1.6;

    // Parte del rectángulo de la mancha que es tinta. Una letra es densa (O, N, H rondan 0,3-0,5);
    // un trozo de enlace doblado (N-C=O) ocupa un rectángulo grande casi vacío
    static final double DENSIDAD_MINIMA = 0.18;

    private LocalizadorLetras() {}

    public static List<Mancha> encontrar(BufferedImage imagen) {
        int ancho = imagen.getWidth();
        int alto = imagen.getHeight();
        // Tipo de tinta de cada píxel: 0 = sin tinta, y si no, su familia de color. Solo se juntan en una
        // mancha píxeles de la misma familia: así una O roja pegada a su enlace negro (lo normal en las
        // imágenes pequeñas de PubChem) sale como mancha propia
        byte[] tinta = new byte[ancho * alto];
        int dibujoX0 = ancho, dibujoY0 = alto, dibujoX1 = -1, dibujoY1 = -1;
        for (int y = 0; y < alto; y++) {
            for (int x = 0; x < ancho; x++) {
                int rgb = imagen.getRGB(x, y);
                int masOscuro = Math.min((rgb >> 16) & 0xFF, Math.min((rgb >> 8) & 0xFF, rgb & 0xFF));
                if (masOscuro < UMBRAL_TINTA) {
                    tinta[y * ancho + x] = familiaDeColor(rgb);
                    dibujoX0 = Math.min(dibujoX0, x);
                    dibujoY0 = Math.min(dibujoY0, y);
                    dibujoX1 = Math.max(dibujoX1, x);
                    dibujoY1 = Math.max(dibujoY1, y);
                }
            }
        }
        if (dibujoX1 < 0) {
            return List.of();
        }
        int ladoDibujo = Math.max(dibujoX1 - dibujoX0, dibujoY1 - dibujoY0) + 1;

        List<Mancha> candidatas = new ArrayList<>();
        for (int[] pixeles : separarManchas(tinta, ancho, alto)) {
            if (pixeles.length < PIXELES_MINIMOS) {
                continue;
            }
            Mancha mancha = describir(imagen, pixeles, ancho);
            boolean esqueleto = Math.max(mancha.ancho(), mancha.alto()) > PROPORCION_MAXIMA * ladoDibujo;
            double densidad = (double) pixeles.length / (mancha.ancho() * mancha.alto());
            if (!esqueleto && densidad >= DENSIDAD_MINIMA && alargamiento(pixeles, ancho) < ALARGAMIENTO_LINEA) {
                candidatas.add(mancha);
            }
        }
        return filtrarPorAltura(candidatas);
    }

    /** 1 = negro o gris, 2 = rojo (oxígeno en PubChem y CDK), 3 = azul (nitrógeno), 4 = otro color. */
    static byte familiaDeColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (r > g + 60 && r > b + 60) {
            return 2;
        }
        if (b > r + 40 && b > g + 40) {
            return 3;
        }
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 60 ? (byte) 4 : (byte) 1;
    }

    /** Agrupa los píxeles de tinta de la misma familia de color que se tocan (8 vecinos). */
    private static List<int[]> separarManchas(byte[] tinta, int ancho, int alto) {
        boolean[] visto = new boolean[tinta.length];
        int[] pendientes = new int[tinta.length];
        List<int[]> manchas = new ArrayList<>();
        for (int inicio = 0; inicio < tinta.length; inicio++) {
            if (tinta[inicio] == 0 || visto[inicio]) {
                continue;
            }
            byte familia = tinta[inicio];
            // Relleno como el cubo de pintura: desde un píxel, se van añadiendo sus vecinos con tinta
            int cuantos = 0;
            int cima = 0;
            pendientes[cima++] = inicio;
            visto[inicio] = true;
            int[] mancha = new int[64];
            while (cima > 0) {
                int actual = pendientes[--cima];
                if (cuantos == mancha.length) {
                    mancha = Arrays.copyOf(mancha, cuantos * 2);
                }
                mancha[cuantos++] = actual;
                int cx = actual % ancho;
                int cy = actual / ancho;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = cx + dx;
                        int ny = cy + dy;
                        if (nx < 0 || ny < 0 || nx >= ancho || ny >= alto) {
                            continue;
                        }
                        int vecino = ny * ancho + nx;
                        if (tinta[vecino] == familia && !visto[vecino]) {
                            visto[vecino] = true;
                            pendientes[cima++] = vecino;
                        }
                    }
                }
            }
            manchas.add(Arrays.copyOf(mancha, cuantos));
        }
        return manchas;
    }

    private static Mancha describir(BufferedImage imagen, int[] pixeles, int ancho) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        long rojo = 0, verde = 0, azul = 0;
        for (int p : pixeles) {
            int x = p % ancho;
            int y = p / ancho;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            int rgb = imagen.getRGB(x, y);
            rojo += (rgb >> 16) & 0xFF;
            verde += (rgb >> 8) & 0xFF;
            azul += rgb & 0xFF;
        }
        int n = pixeles.length;
        return new Mancha(x0, y0, x1 - x0 + 1, y1 - y0 + 1, n, (int) (rojo / n), (int) (verde / n), (int) (azul / n));
    }

    /**
     * Cuánto se estira la mancha en su dirección principal frente a la perpendicular, sea cual sea su giro:
     * se calcula la dispersión de sus píxeles en las dos direcciones naturales de la mancha
     * (los valores propios de su matriz de covarianza) y se dividen.
     */
    static double alargamiento(int[] pixeles, int ancho) {
        double mx = 0, my = 0;
        for (int p : pixeles) {
            mx += p % ancho;
            my += p / ancho;
        }
        mx /= pixeles.length;
        my /= pixeles.length;
        double sxx = 0, syy = 0, sxy = 0;
        for (int p : pixeles) {
            double dx = p % ancho - mx;
            double dy = p / ancho - my;
            sxx += dx * dx;
            syy += dy * dy;
            sxy += dx * dy;
        }
        double traza = sxx + syy;
        double raiz = Math.sqrt((sxx - syy) * (sxx - syy) + 4 * sxy * sxy);
        double mayor = (traza + raiz) / 2;
        double menor = (traza - raiz) / 2;
        return mayor / Math.max(menor, 1e-6);
    }

    /** Las letras de una misma imagen miden parecido: descarta lo que es mucho más bajo o más alto que lo típico. */
    private static List<Mancha> filtrarPorAltura(List<Mancha> candidatas) {
        if (candidatas.isEmpty()) {
            return candidatas;
        }
        // Altura típica: la mediana de las candidatas más altas (la mitad superior),
        // para que los guiones de las cuñas o los subíndices no la hundan
        int[] alturas = candidatas.stream().mapToInt(Mancha::alto).sorted().toArray();
        int tipica = alturas[(alturas.length * 3) / 4];
        return candidatas.stream()
                .filter(m -> m.alto() >= ALTURA_MINIMA * tipica && m.alto() <= ALTURA_MAXIMA * tipica)
                .toList();
    }
}
