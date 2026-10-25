import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

/** Lo que devuelve POST /api/predict (mismo formato que PrediccionController.Respuesta en Java). */
export interface Prediccion {
  compuesto: string;
  nombre: string;
  formula: string;
  /** Nombre sistemático IUPAC: en español si está escrito en compuestos.csv, si no el de PubChem en inglés */
  iupac: string;
  confianza: number;
  ranking: { compuesto: string; nombre: string; probabilidad: number }[];
}

/** Cada compuesto que reconoce el modelo, según GET /api/compuestos. */
export interface Compuesto {
  clave: string;
  nombre: string;
  formula: string;
  iupac: string;
}

@Injectable({ providedIn: 'root' })
export class PrediccionService {
  private readonly http = inject(HttpClient);

  analizar(imagen: File): Observable<Prediccion> {
    const formulario = new FormData();
    formulario.append('imagen', imagen);
    return this.http.post<Prediccion>('/api/predict', formulario);
  }

  compuestos(): Observable<Compuesto[]> {
    return this.http.get<Compuesto[]>('/api/compuestos');
  }
}
