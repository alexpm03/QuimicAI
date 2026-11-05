package com.iaimagenes.backend.prediccion;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.iaimagenes.backend.entrenamiento.Preprocesado;

/**
 * Lee las imágenes que sube la gente al portal sin poner en riesgo el servidor.
 *
 * Un PNG de pocos MB puede medir 30.000 x 30.000 px (mucho blanco se comprime muy bien) y al abrirlo
 * ocuparía varios GB de memoria. Por eso las medidas se leen de la cabecera ANTES de abrir la imagen,
 * y las que se pasan de LADO_MAXIMO se rechazan. Las grandes pero aceptables se reducen a LADO_DE_TRABAJO:
 * la red trabaja a 64-128 px y el lector lee bien las letras con mucha menos resolución.
 */
public final class LecturaImagen {

    static final int LADO_MAXIMO = 5000;
    static final int LADO_DE_TRABAJO = 1500;

    private LecturaImagen() {}

    public static BufferedImage leer(byte[] datos) throws IOException {
        try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(datos))) {
            Iterator<ImageReader> lectores = entrada == null ? null : ImageIO.getImageReaders(entrada);
            if (lectores == null || !lectores.hasNext()) {
                throw new IllegalArgumentException("El archivo no es una imagen que se pueda leer.");
            }
            ImageReader lector = lectores.next();
            try {
                lector.setInput(entrada);
                int ancho = lector.getWidth(0);
                int alto = lector.getHeight(0);
                if (ancho > LADO_MAXIMO || alto > LADO_MAXIMO) {
                    throw new IllegalArgumentException(String.format(
                            "La imagen mide %d x %d píxeles y el máximo son %d x %d.", ancho, alto, LADO_MAXIMO, LADO_MAXIMO));
                }
                return reducir(Preprocesado.aplanarSobreBlanco(lector.read(0)));
            } catch (IOException e) {
                throw new IllegalArgumentException("No se ha podido abrir la imagen: puede que esté dañada.");
            } finally {
                lector.dispose();
            }
        }
    }

    /** Si el lado mayor pasa de LADO_DE_TRABAJO, la reduce manteniendo la proporción (promediando, sin dientes). */
    private static BufferedImage reducir(BufferedImage imagen) {
        int lado = Math.max(imagen.getWidth(), imagen.getHeight());
        if (lado <= LADO_DE_TRABAJO) {
            return imagen;
        }
        double escala = (double) LADO_DE_TRABAJO / lado;
        int ancho = Math.max(1, (int) Math.round(imagen.getWidth() * escala));
        int alto = Math.max(1, (int) Math.round(imagen.getHeight() * escala));
        BufferedImage reducida = new BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB);
        Graphics2D lienzo = reducida.createGraphics();
        lienzo.drawImage(imagen.getScaledInstance(ancho, alto, Image.SCALE_AREA_AVERAGING), 0, 0, null);
        lienzo.dispose();
        return reducida;
    }
}
