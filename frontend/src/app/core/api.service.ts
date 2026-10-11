import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { Analytics, AnalyticsQuery, CreateLinkRequest, LinkDetails, LinkResponse } from './models';

/** Thin typed wrapper over the backend API. Errors arrive as ApiProblem (see errorInterceptor). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiBaseUrl}/api/v1/short-urls`;

  createLink(request: CreateLinkRequest): Observable<LinkResponse> {
    return this.http.post<LinkResponse>(this.base, request);
  }

  getDetails(code: string): Observable<LinkDetails> {
    return this.http.get<LinkDetails>(`${this.base}/${encodeURIComponent(code)}`);
  }

  getAnalytics(code: string, query: AnalyticsQuery = {}): Observable<Analytics> {
    let params = new HttpParams();
    if (query.from) {
      params = params.set('from', query.from);
    }
    if (query.to) {
      params = params.set('to', query.to);
    }
    if (query.granularity) {
      params = params.set('granularity', query.granularity);
    }
    return this.http.get<Analytics>(`${this.base}/${encodeURIComponent(code)}/analytics`, { params });
  }
}
