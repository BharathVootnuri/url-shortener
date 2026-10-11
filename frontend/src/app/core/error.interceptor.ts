import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { ApiProblem, problemMessage } from './api-problem';

/** Turns every failed HTTP call into an {@link ApiProblem}. */
export const errorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(catchError((error: unknown) => throwError(() => toApiProblem(error))));

export function toApiProblem(error: unknown): ApiProblem {
  if (error instanceof ApiProblem) {
    return error;
  }
  if (!(error instanceof HttpErrorResponse)) {
    return new ApiProblem(0, 'UNKNOWN', 'Something unexpected happened. Please try again.');
  }
  if (error.status === 0) {
    return new ApiProblem(0, 'NETWORK_ERROR', problemMessage('NETWORK_ERROR', 0, null));
  }

  const body: unknown = error.error;
  const record = body !== null && typeof body === 'object' ? (body as Record<string, unknown>) : {};
  const code = typeof record['code'] === 'string' ? record['code'] : statusCode(error.status);
  const detail = typeof record['detail'] === 'string' ? record['detail'] : null;
  const traceId = typeof record['traceId'] === 'string' ? record['traceId'] : null;
  const retryAfter = Number.parseInt(error.headers?.get('Retry-After') ?? '', 10);

  return new ApiProblem(
    error.status,
    code,
    problemMessage(code, error.status, detail),
    traceId,
    Number.isFinite(retryAfter) ? retryAfter : null,
  );
}

/** Fallback code when the body is not a problem document (e.g. an HTML error page from a proxy). */
function statusCode(status: number): string {
  switch (status) {
    case 404:
      return 'LINK_NOT_FOUND';
    case 410:
      return 'LINK_EXPIRED';
    case 429:
      return 'RATE_LIMITED';
    default:
      return status >= 500 ? 'INTERNAL_ERROR' : 'UNKNOWN';
  }
}
