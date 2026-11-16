import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Book } from '../models/book.model';

/** Semantic ("natural language") search over the catalogue: POST /api/v3/ai/books/search. */
@Injectable({ providedIn: 'root' })
export class AiSearchService {
  private http = inject(HttpClient);
  private apiUrl = `${environment.apiUrl}/ai/books`;

  search(query: string): Observable<Book[]> {
    return this.http.post<Book[]>(`${this.apiUrl}/search`, { query });
  }
}
