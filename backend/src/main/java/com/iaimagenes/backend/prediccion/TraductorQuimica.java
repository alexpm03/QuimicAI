package com.iaimagenes.backend.prediccion;

import java.util.List;

import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.Pipeline;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;

/**
 * Traduce entre Java y la red: imagen -> tensor preprocesado (entrada)
 * y 6 puntuaciones -> probabilidades con nombre (salida).
 */
public class TraductorQuimica implements Translator<Image, Classifications> {

    private final Pipeline preprocesado;
    private final List<String> synset;

    public TraductorQuimica(Pipeline preprocesado, List<String> synset) {
        this.preprocesado = preprocesado;
        this.synset = synset;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, Image imagen) {
        NDArray pixeles = imagen.toNDArray(ctx.getNDManager(), Image.Flag.COLOR);
        return preprocesado.transform(new NDList(pixeles));
    }

    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList salida) {
        NDArray probabilidades = salida.singletonOrThrow().softmax(0);
        return new Classifications(synset, probabilidades);
    }
}
