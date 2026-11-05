package com.iaimagenes.backend.prediccion;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.iaimagenes.backend.lector.CombinadorLetras;
import com.iaimagenes.backend.lector.LectorLetras;

/**
 * El lector de letras dentro del portal: lee las O y N de la imagen y corrige la predicción de la red.
 * Si no hay lector entrenado en lector.ruta, el portal funciona igual, solo con la red.
 */
@Service
public class LectorService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LectorService.class);

    /**
     * La predicción final y lo que leyó el lector (null si no hay lector). desconocida es true si ninguna
     * molécula de la lista tiene esas O y N: seguramente la imagen es de una molécula que no conocemos.
     */
    public record Corregida(Prediccion prediccion, LectorLetras.Resultado letras, boolean desconocida) {}

    private final LectorLetras lector;
    private final CombinadorLetras combinador;

    public LectorService(@Value("${lector.ruta}") String ruta, @Value("${datos.compuestos}") String compuestos)
            throws Exception {
        Path carpeta = Path.of(ruta);
        if (Files.exists(carpeta.resolve("synset.txt"))) {
            lector = new LectorLetras(carpeta);
            combinador = new CombinadorLetras(Path.of(compuestos));
        } else {
            log.warn("No hay lector de letras en {}: el portal usará solo la red. Entrénalo con EntrenarLector.",
                    carpeta.toAbsolutePath().normalize());
            lector = null;
            combinador = null;
        }
    }

    public Corregida corregir(Prediccion red, BufferedImage imagen) throws Exception {
        if (lector == null) {
            return new Corregida(red, null, false);
        }
        LectorLetras.Resultado letras = lector.leerImagen(imagen);
        return new Corregida(combinador.reordenar(red, letras), letras, !combinador.algunoEncaja(letras));
    }

    @Override
    public void close() {
        if (lector != null) {
            lector.close();
        }
    }
}
