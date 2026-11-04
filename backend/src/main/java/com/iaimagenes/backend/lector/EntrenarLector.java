package com.iaimagenes.backend.lector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import ai.djl.Model;
import ai.djl.basicdataset.cv.classification.ImageFolder;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.ndarray.types.Shape;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.evaluator.Accuracy;
import ai.djl.training.listener.TrainingListener;
import ai.djl.training.loss.Loss;
import ai.djl.training.optimizer.Optimizer;
import ai.djl.training.tracker.Tracker;
import ai.djl.training.util.ProgressBar;

/**
 * Entrena la mini red que lee letras con ../dataset_letras (de GenerarLetras) y la guarda en ../modelo_lector.
 * Es muy pequeña (24 x 24 px, dos capas convolucionales): entrena en pocos minutos.
 */
public class EntrenarLector {

    private static final int LOTE = 64;
    private static final int EPOCAS_MAXIMAS = 15;
    private static final int PACIENCIA = 3;
    private static final float MEJORA_MINIMA = 0.002f;

    public static void main(String[] args) throws Exception {
        Path dataset = Path.of("../dataset_letras");
        Path carpetaModelo = Path.of("../modelo_lector");
        ImageFolder train = cargar(dataset.resolve("train"), true);
        ImageFolder test = cargar(dataset.resolve("test"), false);
        List<String> clases = train.getSynset();
        System.out.println("Clases (" + clases.size() + "): " + clases);

        try (Model model = Model.newInstance(LectorLetras.NOMBRE)) {
            model.setBlock(LectorLetras.red(clases.size()));
            DefaultTrainingConfig config = new DefaultTrainingConfig(Loss.softmaxCrossEntropyLoss())
                    .addEvaluator(new Accuracy())
                    .optOptimizer(Optimizer.adam().optLearningRateTracker(Tracker.fixed(0.001f)).build())
                    .addTrainingListeners(TrainingListener.Defaults.logging());

            try (Trainer trainer = model.newTrainer(config)) {
                trainer.initialize(new Shape(1, 3, Glifos.LADO, Glifos.LADO));
                float mejor = -1;
                int sinMejora = 0;
                for (int epoca = 1; epoca <= EPOCAS_MAXIMAS; epoca++) {
                    EasyTrain.fit(trainer, 1, train, test);
                    Map<String, Float> resultado = trainer.getTrainingResult().getEvaluations();
                    float validacion = resultado.get("validate_Accuracy");
                    System.out.printf("%n== Época %d/%d: entrenamiento %.1f %%, validación %.1f %%%n", epoca,
                            EPOCAS_MAXIMAS, resultado.get("train_Accuracy") * 100, validacion * 100);
                    if (validacion > mejor + MEJORA_MINIMA) {
                        mejor = validacion;
                        sinMejora = 0;
                        guardar(model, carpetaModelo, clases, epoca);
                        System.out.println("   Mejor resultado hasta ahora: modelo guardado.");
                    } else if (++sinMejora >= PACIENCIA) {
                        System.out.println("   " + PACIENCIA + " épocas sin mejorar: paramos aquí.");
                        break;
                    }
                }
                System.out.printf("%nMejor validación: %.1f %%. Lector en %s%n", mejor * 100,
                        carpetaModelo.toAbsolutePath().normalize());
            }
        }
    }

    private static void guardar(Model model, Path carpeta, List<String> clases, int epoca) throws Exception {
        Files.createDirectories(carpeta);
        try (Stream<Path> archivos = Files.list(carpeta)) {
            for (Path archivo : archivos.filter(a -> a.toString().endsWith(".params")).toList()) {
                Files.delete(archivo);
            }
        }
        model.setProperty("Epoch", String.valueOf(epoca));
        model.save(carpeta, LectorLetras.NOMBRE);
        Files.write(carpeta.resolve("synset.txt"), clases);
    }

    private static ImageFolder cargar(Path carpeta, boolean barajar) throws Exception {
        if (!Files.exists(carpeta)) {
            throw new IllegalStateException("No existe " + carpeta.toAbsolutePath().normalize()
                    + ". Genera antes el dataset con GenerarLetras.");
        }
        ImageFolder dataset = ImageFolder.builder()
                .setRepositoryPath(carpeta)
                .addTransform(new ToTensor())
                .setSampling(LOTE, barajar)
                .build();
        dataset.prepare(new ProgressBar());
        return dataset;
    }
}
