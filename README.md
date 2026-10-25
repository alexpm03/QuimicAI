# QuimicAI: reconocer fórmulas estructurales con una red neuronal en Java

Portal web para estudiantes de química: subes el dibujo 2D de una molécula (como los de Wikipedia o PubChem)
y una red neuronal te dice qué compuesto es, con su fórmula molecular y su nombre IUPAC.

Proyecto personal de aprendizaje. Todo el aprendizaje automático está hecho en **Java** con
[DJL](https://djl.ai) (Deep Java Library) sobre PyTorch; el portal, en **Angular**.

- **30 compuestos**: disolventes, fármacos, neurotransmisores y biomoléculas (lista en [`datos/compuestos.csv`](datos/compuestos.csv)).
- **Dos redes** para comparar:
  - **Propia**: una CNN diseñada capa a capa y entrenada desde cero (64 × 64 px, ~550.000 pesos).
  - **ResNet18**: red preentrenada con ImageNet y ajustada a nuestros compuestos (*transfer learning*, 128 × 128 px, ~11 millones de pesos).
- **Laboratorio**: una página del portal para ver la red por dentro, capa a capa.

## Cómo funciona

```
compuestos.csv ──► DescargarCompuestos ──► compuestos_pubchem.csv   (SMILES, fórmula y nombre IUPAC de PubChem)
                                                │
                                                ▼
                                          GenerarDataset             (CDK dibuja cada molécula cientos de veces:
                                                │                     giros, fuentes, grosores, fondos, tamaños)
                                                ▼
                                            Entrenar                 (red propia o ResNet18, con parada temprana)
                                                │
                                                ▼
                                             modelo/                 (pesos + synset.txt + modelo.properties)
                                                │
                      ┌─────────────────────────┴─────────────────────────┐
                      ▼                                                   ▼
               EvaluarPruebas                                    Backend Spring Boot
        (examen con imágenes reales)                    POST /api/predict, GET /api/compuestos,
                                                         GET /api/laboratorio/arquitectura
                                                                          │
                                                                          ▼
                                                                Portal Angular (4200)
```

**La red nunca ve imágenes descargadas al entrenar**: aprende solo con dibujos generados por
[CDK](https://cdk.github.io) a partir del SMILES de cada compuesto. Las imágenes reales de PubChem y
Wikipedia se reservan para el examen, que es la única medida fiable de cómo funciona de verdad.

Antes de entrar en la red, cada imagen pasa por el mismo preprocesado (`Preprocesado.java`), tanto al entrenar
como al predecir: fondo transparente a blanco, invertir (fondo = 0, trazos = 1), borrar grises y cuadros,
recortar al dibujo, hacerla cuadrada y reducirla al tamaño de la red.

## Estructura

```
backend/     Spring Boot 4 + Java 21 + DJL 0.33 (PyTorch, CPU) + CDK 2.10
  entrenamiento/   DescargarCompuestos, AmpliarExamen, GenerarDataset, Entrenar, EvaluarPruebas,
                   Preprocesado, Arquitecturas, ConfigModelo
  prediccion/      ClasificadorService, CatalogoCompuestos, PrediccionController
  laboratorio/     InspectorRed, LaboratorioController
frontend/    Angular 22: páginas "Identificar" y "Laboratorio"
datos/       compuestos.csv (lista editable, con nombres IUPAC en español)
pruebas/     examen con imágenes reales (se descarga; no está en el repositorio)
```

## Requisitos

- Java 21 (JDK) y la variable `JAVA_HOME` apuntando a él
- Node.js 20 o superior y Angular CLI (`npm install -g @angular/cli`)
- Conexión a internet la primera vez: Maven, PyTorch (~200 MB), ResNet18 (~45 MB), PubChem y Wikipedia

No hace falta instalar Maven: el backend trae su propio `mvnw`.

## Puesta en marcha (Windows, cmd, desde `backend\`)

Los datasets, los modelos y las imágenes de prueba no están en el repositorio: se generan así.

```bat
chcp 65001

:: 1. Datos de los compuestos desde PubChem (~40 s)
mvnw.cmd compile exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.DescargarCompuestos

:: 2. Examen con imágenes reales de PubChem y Wikipedia (~2 min)
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.AmpliarExamen

:: 3a. Red propia: dataset de 64 px y entrenamiento desde cero
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.GenerarDataset
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.Entrenar

:: 3b. O ResNet18: dataset de 128 px y transfer learning
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.GenerarDataset -Dexec.args=resnet
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.Entrenar -Dexec.args=resnet

:: 4. Examen con imágenes reales
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.EvaluarPruebas

:: 5. Backend en http://localhost:8080
mvnw.cmd spring-boot:run
```

Y el portal, desde `frontend\`:

```bat
npm install
ng serve
```

Abre http://localhost:4200. El modelo que se usa es siempre el de `modelo/`; `Entrenar continuar` sigue
entrenando el guardado en lugar de empezar de cero.

## Resultados y lo aprendido por el camino

Examen con 93 imágenes reales (PubChem a 300 y 100 px, y Wikipedia en español e inglés):

| Modelo | Validación con dibujos generados | Examen real |
|---|---|---|
| Red propia, 64 px, 3 bloques convolucionales | 90 % | **54 de 93 (58 %)** |
| Red propia, 96 px, 4 bloques | 91 % | 47 de 93 (51 %) |
| ResNet18 preentrenada, 128 px | *pendiente* | *pendiente* |

Algunas lecciones que dejó el proceso:

- **El examen sintético engaña.** Con dibujos generados la red llega al 90-96 %, pero con imágenes reales se
  queda en torno al 55 %: aprende muy bien el estilo de CDK y le cuesta con otros (*domain gap*).
- **Más capacidad no siempre es mejor.** La red de 96 px acertaba más en el examen sintético y menos en el real:
  usaba su capacidad extra para memorizar detalles del estilo de dibujo.
- **Los datos importan más que la red.** Recortar la imagen al dibujo, limpiar el fondo y pintar de blanco los
  PNG transparentes arreglaron más fallos que cualquier cambio en las capas.
- **Mirar lo que ve la red.** `EvaluarPruebas` guarda en `pruebas/vista_red/` cada imagen tal y como entra en
  la red; casi todos los fallos se entendieron mirando ahí.

## Datos y licencias de terceros

- Estructuras, fórmulas y nombres IUPAC: [PubChem](https://pubchem.ncbi.nlm.nih.gov) (NIH), de dominio público.
- Imágenes del examen de Wikipedia/Wikimedia Commons: en su mayoría CC BY-SA. No se incluyen en el repositorio;
  `AmpliarExamen` las descarga en local.
- Pesos de ResNet18: modelo de torchvision distribuido por el zoo de DJL; se descargan al entrenar.
