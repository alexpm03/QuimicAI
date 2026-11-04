# QuimicAI: reconocer fórmulas estructurales con una red neuronal en Java

Portal web para estudiantes de química: subes el dibujo 2D de una molécula (como los de Wikipedia o PubChem)
y una red neuronal te dice qué compuesto es, con su fórmula molecular y su nombre IUPAC.

Proyecto personal de aprendizaje. Todo el aprendizaje automático está hecho en **Java** con
[DJL](https://djl.ai) (Deep Java Library) sobre PyTorch; el portal, en **Angular**.

- **30 compuestos**: disolventes, fármacos, neurotransmisores y biomoléculas (lista en [`datos/compuestos.csv`](datos/compuestos.csv)).
- **Dos redes** para comparar:
  - **Propia**: una CNN diseñada capa a capa y entrenada desde cero (64 × 64 px, ~550.000 pesos).
  - **ResNet18**: red preentrenada con ImageNet y ajustada a nuestros compuestos (*transfer learning*, 128 × 128 px, ~11 millones de pesos).
- **Lector de letras**: un módulo aparte que localiza y lee las letras del dibujo (O, N, H, C y subíndices),
  cuenta los oxígenos y nitrógenos y corrige a la red cuando propone una molécula que no los tiene.
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
                                                │      GenerarLetras ──► EntrenarLector ──► modelo_lector/
                                                │                                                │
                      ┌─────────────────────────┴──────────────────────┬─────────────────────────┘
                      ▼                                                ▼
               EvaluarPruebas                                 Backend Spring Boot
        (examen con imágenes reales,                 POST /api/predict, GET /api/compuestos,
         AnalizadorFallos + historial)                GET /api/laboratorio/arquitectura
                                                                       │
                                                                       ▼
                                                             Portal Angular (4200)
```

### El lector de letras

Lo que distingue a muchas moléculas parecidas son las letras: el fenol tiene un OH que el tolueno no tiene,
la adrenalina tiene una O más que la dopamina. A 64 px una "O" son 3 o 4 píxeles y la red se fija más en la
forma de las líneas. El lector lee las letras aparte, sobre la imagen original:

1. **Localizar** (`LocalizadorLetras`, sin red): las letras son manchas pequeñas, compactas y separadas de los
   enlaces; las líneas son largas y finas. Solo se juntan píxeles del mismo color, para separar una O roja
   pegada a su enlace negro.
2. **Separar y recortar** (`Glifos`): si una mancha mide lo que dos letras ("OH"), se parte por la columna
   con menos tinta; cada letra se normaliza a 24 × 24 px.
3. **Leer** (`LectorLetras`): una CNN pequeña entrenada con letras dibujadas por Java en 12 fuentes
   (`GenerarLetras`). En las imágenes diminutas de PubChem los átomos son cuadraditos de color: ahí manda
   el color (rojo O, azul N). Cada número pequeño a la derecha de una letra es su subíndice (CH₃, NH₂).
4. **Combinar** (`CombinadorLetras`): cada candidato de la red se multiplica por 0,05 por cada O o N que le
   sobre o le falte, y se reparten de nuevo los porcentajes.

### Medir antes de cambiar

`EvaluarPruebas` clasifica cada fallo (`AnalizadorFallos`): imagen pequeña, estilo Wikipedia, molécula casi
igual (parecido químico de CDK) o "cajón de sastre". Cada examen se apunta en `historial/examenes.csv`; con
`etiqueta=` se agrupan varios entrenamientos del mismo experimento para ver cuánto mueve el azar la nota.

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
  lector/          LocalizadorLetras, Glifos, GenerarLetras, EntrenarLector, LectorLetras,
                   CombinadorLetras, ProbarLocalizador, ProbarLector
  analisis/        AnalizadorFallos, Historial
  prediccion/      ClasificadorService, LectorService, CatalogoCompuestos, PrediccionController
  laboratorio/     InspectorRed, LaboratorioController
frontend/    Angular 22: páginas "Identificar" y "Laboratorio"
datos/       compuestos.csv (lista editable, con nombres IUPAC en español)
historial/   examenes.csv: un examen por fila, con aciertos por procedencia y por tipo de fallo
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

:: 4. Lector de letras: dataset de letras (~1 min) y mini red (pocos minutos)
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.lector.GenerarLetras
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.lector.EntrenarLector

:: 5. Examen con imágenes reales (sin y con lector)
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.EvaluarPruebas
mvnw.cmd exec:java -Dexec.mainClass=com.iaimagenes.backend.entrenamiento.EvaluarPruebas -Dexec.args="lector=../modelo_lector etiqueta=con_lector"

:: 6. Backend en http://localhost:8080 (usa el lector si existe ../modelo_lector)
mvnw.cmd spring-boot:run
```

Y el portal, desde `frontend\`:

```bat
npm install
ng serve
```

Abre http://localhost:4200. El modelo que se usa es siempre el de `modelo/`; `Entrenar continuar` sigue
entrenando el guardado en lugar de empezar de cero, y `Entrenar salida=../repes/propia_1` guarda en otra
carpeta para repetir un entrenamiento sin pisar el del portal.

## Resultados y lo aprendido por el camino

Examen con 88 imágenes reales: PubChem a 300 y 100 px, y Wikipedia en español e inglés. Se quitaron a mano
las que no son fórmulas 2D planas (modelos 3D, proyecciones de Haworth, iones; ver `descartadas.txt`).
La red propia se entrenó 3 veces para medir el azar:

| Modelo | Solo la red | Con lector de letras |
|---|---|---|
| Red propia, 64 px (3 entrenamientos) | 46 / 46 / 49, media **47 (53 %)** | 62 / 68 / 69, media **66 (75 %)** |
| ResNet18 preentrenada, 128 px | **54 (61 %)** | **73 (83 %)** |

El lector cuenta bien las O y N en 85 de las 88 imágenes. Con el examen anterior de 93 imágenes, la red
propia de 96 px y 4 bloques sacaba 47 de 93 frente a los 54 de una de 64 px.

Algunas lecciones que dejó el proceso:

- **El examen sintético engaña.** Con dibujos generados la red llega al 90-96 %, pero con imágenes reales se
  queda en torno al 55 %: aprende muy bien el estilo de CDK y le cuesta con otros (*domain gap*).
- **Más capacidad no siempre es mejor.** La red de 96 px acertaba más en el examen sintético y menos en el real:
  usaba su capacidad extra para memorizar detalles del estilo de dibujo.
- **Los datos importan más que la red.** Recortar la imagen al dibujo, limpiar el fondo y pintar de blanco los
  PNG transparentes arreglaron más fallos que cualquier cambio en las capas.
- **Una red 20 veces más grande no arregla el *domain gap*.** ResNet18 preentrenada acierta más con dibujos
  grandes y de Wikipedia, pero menos con los pequeños, y en total queda casi empatada con la red propia
  (55 frente a 54 de 93), entrenando 10 veces más lento.
- **El azar también entrena.** La misma red con el mismo dataset sacó 46, 46 y 49: sin repetir, un cambio
  de 3 aciertos no demuestra nada. Una vez "comprobamos" que un filtro empeoraba la red comparando con un
  modelo que había tenido suerte.
- **Buscar qué tienen en común los fallos.** Clasificarlos mostró que la mayoría se repetían en todos los
  entrenamientos (no eran azar) y que muchos eran parejas que solo se distinguen por una letra. De ahí el lector.
- **Un módulo especializado puede más que una red más grande.** Leer las letras aparte subió 19 aciertos
  tanto a la red propia como a ResNet.
- **Mirar lo que ve la red.** `EvaluarPruebas` guarda en `pruebas/vista_red/` cada imagen tal y como entra en
  la red; casi todos los fallos se entendieron mirando ahí.

## Datos y licencias de terceros

- Estructuras, fórmulas y nombres IUPAC: [PubChem](https://pubchem.ncbi.nlm.nih.gov) (NIH), de dominio público.
- Imágenes del examen de Wikipedia/Wikimedia Commons: en su mayoría CC BY-SA. No se incluyen en el repositorio;
  `AmpliarExamen` las descarga en local.
- Pesos de ResNet18: modelo de torchvision distribuido por el zoo de DJL; se descargan al entrenar.
