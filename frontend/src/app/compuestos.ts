/** Un trozo de fórmula: el símbolo del elemento y, si lo hay, su subíndice ("C" + "8"). */
export interface ParteFormula {
  elemento: string;
  indice: string;
}

/** "C8H10N4O2" -> C·8, H·10, N·4, O·2, para pintar los números como subíndices. */
export function partesFormula(formula: string): ParteFormula[] {
  return [...formula.matchAll(/([A-Z][a-z]?)(\d*)/g)].map(([, elemento, indice]) => ({ elemento, indice }));
}

/** 0.99998 -> "99,998 %", 0.0015 -> "0,15 %", valores diminutos -> "< 0,01 %". */
export function formatearPorcentaje(probabilidad: number): string {
  const valor = probabilidad * 100;
  if (valor < 0.01) {
    return '< 0,01 %';
  }
  const decimales = valor >= 99 && valor < 100 ? 3 : 2;
  return `${valor.toLocaleString('es-ES', { maximumFractionDigits: decimales })} %`;
}
