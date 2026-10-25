package com.iaimagenes.backend.entrenamiento;

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

public class Entrenar {

    private static final int LOTE = 32;
    private static final int EPOCAS_MAXIMAS = 20;

    // Parada temprana: si en estas épocas seguidas la validación no mejora al menos MEJORA_MINIMA, paramos
    private static final int PACIENCIA = 3;
    private static final float MEJORA_MINIMA = 0.002f;

    // Desde cero se aprende más rápido; al continuar se ajusta con pasos más pequeños para no estropear lo aprendido
    private static final float APRENDIZAJE_DESDE_CERO = 0.001f;
    private static final float APRENDIZAJE_CONTINUANDO = 0.0003f;
    // Una red preentrenada ya "sabe ver": pasos pequeños desde el principio para no borrar lo que trae de ImageNet
    private static final float APRENDIZAJE_PREENTRENADA = 0.0003f;
    private static final float APRENDIZAJE_PREENTRENADA_CONTINUANDO = 0.0001f;

    /**
     * Uso:  Entrenar                    -> red propia (64 px) desde cero, con ../dataset
     *       Entrenar continuar          -> red propia, partiendo del modelo guardado en ../modelo
     *       Entrenar resnet             -> ResNet18 preentrenada (128 px), con ../dataset_128
     *       Entrenar resnet continuar   -> ResNet18, partiendo del modelo guardado en ../modelo
     */
    public static void main(String[] args) throws Exception {
        List<String> opciones = List.of(args);
        ConfigModelo red = opciones.contains("resnet") ? ConfigModelo.RESNET18 : ConfigModelo.PROPIA;
        boolean continuar = opciones.contains("continuar");
        Path dataset = red.carpetaDataset();
        Path carpetaModelo = Path.of("../modelo");
        System.out.println("Red: " + red.arquitectura() + " a " + red.tamano() + " px, dataset " + dataset);

        ImageFolder train = cargar(dataset.resolve("train"), true, red);
        ImageFolder test = cargar(dataset.resolve("test"), false, red);
        List<String> clases = train.getSynset();
        System.out.println("Clases (" + clases.size() + "): " + clases);

        try (Model model = Model.newInstance("quimica-cnn")) {
            model.setBlock(Arquitecturas.crear(red, clases.size()));

            if (continuar) {
                continuar = cargarModeloAnterior(model, carpetaModelo, clases, red);
            }
            System.out.println(continuar
                    ? "Continuando desde el modelo guardado."
                    : red.preentrenada()
                            ? "Empezando desde ResNet18 con los pesos de ImageNet y una última capa nueva."
                            : "Empezando desde cero, con pesos aleatorios.");

            float aprendizaje = red.preentrenada()
                    ? (continuar ? APRENDIZAJE_PREENTRENADA_CONTINUANDO : APRENDIZAJE_PREENTRENADA)
                    : (continuar ? APRENDIZAJE_CONTINUANDO : APRENDIZAJE_DESDE_CERO);
            DefaultTrainingConfig config = new DefaultTrainingConfig(Loss.softmaxCrossEntropyLoss())
                    .addEvaluator(new Accuracy())
                    .optOptimizer(Optimizer.adam().optLearningRateTracker(Tracker.fixed(aprendizaje)).build())
                    .addTrainingListeners(TrainingListener.Defaults.logging());

            try (Trainer trainer = model.newTrainer(config)) {
                // Solo inicializa los pesos que no estén ya cargados (del modelo anterior o de ImageNet)
                trainer.initialize(new Shape(1, 3, red.tamano(), red.tamano()));

                float mejor = -1;
                int epocasSinMejora = 0;
                for (int epoca = 1; epoca <= EPOCAS_MAXIMAS; epoca++) {
                    EasyTrain.fit(trainer, 1, train, test);

                    Map<String, Float> resultado = trainer.getTrainingResult().getEvaluations();
                    float aciertoTrain = resultado.get("train_Accuracy");
                    float aciertoTest = resultado.get("validate_Accuracy");
                    System.out.printf("%n== Época %d/%d: entrenamiento %.1f %%, validación %.1f %%%n",
                            epoca, EPOCAS_MAXIMAS, aciertoTrain * 100, aciertoTest * 100);

                    if (aciertoTest > mejor + MEJORA_MINIMA) {
                        mejor = aciertoTest;
                        epocasSinMejora = 0;
                        guardar(model, carpetaModelo, clases, epoca, red);
                        System.out.println("   Mejor resultado hasta ahora: modelo guardado.");
                    } else if (++epocasSinMejora >= PACIENCIA) {
                        System.out.println("   " + PACIENCIA + " épocas sin mejorar: paramos aquí.");
                        break;
                    } else {
                        System.out.println("   Sin mejora (" + epocasSinMejora + " de " + PACIENCIA + ").");
                    }
                }
                System.out.printf("%nMejor validación: %.1f %%. Modelo en %s%n",
                        mejor * 100, carpetaModelo.toAbsolutePath().normalize());
            }
        }
    }

    /** Carga los pesos guardados si son de la misma red y las mismas clases; si no, devuelve false. */
    private static boolean cargarModeloAnterior(Model model, Path carpetaModelo, List<String> clases, ConfigModelo red)
            throws Exception {
        Path synset = carpetaModelo.resolve("synset.txt");
        if (!Files.exists(synset) || !Files.readAllLines(synset).equals(clases)) {
            System.out.println("El modelo guardado no tiene las mismas clases que el dataset.");
            return false;
        }
        if (!ConfigModelo.leer(carpetaModelo).equals(red)) {
            System.out.println("El modelo guardado es de otra red (" + ConfigModelo.leer(carpetaModelo).arquitectura() + ").");
            return false;
        }
        try {
            model.load(carpetaModelo, "quimica-cnn");
            return true;
        } catch (Exception e) {
            // Pasa al cambiar la red (más capas, otra resolución): los pesos guardados no encajan
            System.out.println("El modelo guardado tiene otra arquitectura y no se puede continuar desde él.");
            return false;
        }
    }

    /** Sustituye el modelo guardado por el actual: solo queda un archivo de pesos, el de la mejor época. */
    private static void guardar(Model model, Path carpetaModelo, List<String> clases, int epoca, ConfigModelo red)
            throws Exception {
        Files.createDirectories(carpetaModelo);
        try (Stream<Path> archivos = Files.list(carpetaModelo)) {
            for (Path archivo : archivos.filter(a -> a.toString().endsWith(".params")).toList()) {
                Files.delete(archivo);
            }
        }
        model.setProperty("Epoch", String.valueOf(epoca));
        model.save(carpetaModelo, "quimica-cnn");
        Files.write(carpetaModelo.resolve("synset.txt"), clases);
        red.guardar(carpetaModelo);
    }

    private static ImageFolder cargar(Path carpeta, boolean barajar, ConfigModelo red) throws Exception {
        if (!Files.exists(carpeta)) {
            throw new IllegalStateException("No existe " + carpeta.toAbsolutePath().normalize()
                    + ". Genera antes el dataset de " + red.tamano() + " px con GenerarDataset.");
        }
        // Las imágenes ya vienen preprocesadas en su tamaño final desde GenerarDataset:
        // solo hay que pasarlas a tensor y, si la red es preentrenada, normalizarlas como en ImageNet
        ImageFolder dataset = ImageFolder.builder()
                .setRepositoryPath(carpeta)
                .addTransform(new ToTensor())
                .addTransform(Preprocesado.paraLaRed(red))
                .setSampling(LOTE, barajar)
                .build();
        dataset.prepare(new ProgressBar());
        return dataset;
    }
}
