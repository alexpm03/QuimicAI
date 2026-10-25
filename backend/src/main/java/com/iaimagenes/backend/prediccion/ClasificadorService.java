package com.iaimagenes.backend.prediccion;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.iaimagenes.backend.entrenamiento.Arquitecturas;
import com.iaimagenes.backend.entrenamiento.ConfigModelo;
import com.iaimagenes.backend.entrenamiento.Preprocesado;

import ai.djl.Model;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.nn.Block;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;

@Service
public class ClasificadorService implements AutoCloseable {

    private final Model model;
    private final Translator<Image, Classifications> translator;
    private final Path rutaModelo;
    private final List<String> synset;
    private final ConfigModelo config;

    public ClasificadorService(@Value("${modelo.ruta}") String ruta) throws Exception {
        rutaModelo = Path.of(ruta);
        synset = Files.readAllLines(rutaModelo.resolve("synset.txt"));
        // Qué red hay guardada (propia o ResNet18) y a qué tamaño trabaja
        config = ConfigModelo.leer(rutaModelo);

        model = Model.newInstance("quimica-cnn");
        model.setBlock(Arquitecturas.crear(config, synset.size()));
        model.load(rutaModelo, "quimica-cnn");

        translator = new TraductorQuimica(Preprocesado.crear(config), synset);
    }

    public ConfigModelo config() {
        return config;
    }

    public Prediccion clasificar(InputStream entrada) throws IOException, TranslateException {
        BufferedImage original = ImageIO.read(entrada);
        if (original == null) {
            throw new IllegalArgumentException("El archivo no es una imagen que se pueda leer.");
        }

        Image imagen = ImageFactory.getInstance().fromImage(Preprocesado.aplanarSobreBlanco(original));

        try (Predictor<Image, Classifications> predictor = model.newPredictor(translator)) {
            Classifications resultado = predictor.predict(imagen);

            List<Prediccion.Probabilidad> ranking = resultado.items().stream()
                    .map(c -> new Prediccion.Probabilidad(c.getClassName(), c.getProbability()))
                    .sorted((a, b) -> Double.compare(b.probabilidad(), a.probabilidad()))
                    .toList();

            Prediccion.Probabilidad mejor = ranking.get(0);
            return new Prediccion(mejor.compuesto(), mejor.probabilidad(), ranking);
        }
    }

    /** La red cargada, para que el laboratorio pueda inspeccionar sus capas. */
    public Block red() {
        return model.getBlock();
    }

    public List<String> clases() {
        return synset;
    }

    /** Nombre del archivo de pesos cargado, por ejemplo "quimica-cnn-0008.params". */
    public String archivoPesos() throws IOException {
        try (Stream<Path> archivos = Files.list(rutaModelo)) {
            return archivos.map(a -> a.getFileName().toString())
                    .filter(nombre -> nombre.endsWith(".params"))
                    .findFirst()
                    .orElse("");
        }
    }

    @Override
    public void close() {
        model.close();
    }
}
