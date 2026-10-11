/**
 * The single error shape the UI works with. The HTTP error interceptor converts every failed request
 * (RFC 7807 problem+json from the backend, network failures, unexpected bodies) into one of these, so
 * components never have to inspect HttpErrorResponse.
 */
export class ApiProblem extends Error {
  constructor(
    /** HTTP status; 0 when the server could not be reached at all. */
    readonly status: number,
    /** Stable machine-readable code from the backend (e.g. LINK_NOT_FOUND) or NETWORK_ERROR / UNKNOWN. */
    readonly code: string,
    /** Human-readable text that is safe to show to the user. */
    message: string,
    /** Correlation id to quote when reporting a problem, if the server supplied one. */
    readonly traceId: string | null = null,
    /** Seconds from the Retry-After header, if present. */
    readonly retryAfterSeconds: number | null = null,
  ) {
    super(message);
    this.name = 'ApiProblem';
  }

  /** True for failures where trying again later may help. */
  get retryable(): boolean {
    return this.status === 0 || this.status === 429 || this.status >= 500;
  }
}

/**
 * Wording shown to users per backend error code. Codes with user-fixable input errors fall back to the
 * server's own `detail` (which never contains internals); everything else gets fixed text.
 */
const FRIENDLY_MESSAGES: Record<string, string> = {
  LINK_NOT_FOUND: 'We could not find a link with that code. Check for typos in the address.',
  LINK_EXPIRED: 'This link has expired and no longer redirects.',
  RATE_LIMITED: 'Too many requests from your network. Please wait a moment and try again.',
  PAYLOAD_TOO_LARGE: 'The request was too large.',
  DATABASE_UNAVAILABLE: 'The service is temporarily unavailable. Please try again shortly.',
  CODE_ALLOCATION_FAILED: 'We could not create the link right now. Please try again.',
  DATABASE_ERROR: 'Something went wrong on our side. Please try again later.',
  INTERNAL_ERROR: 'Something went wrong on our side. Please try again later.',
  NETWORK_ERROR: 'Cannot reach the server. Check your connection and try again.',
};

/** Codes whose server-provided `detail` is meant for end users (validation feedback). */
const DETAIL_IS_USER_FACING = new Set([
  'INVALID_URL',
  'INVALID_EXPIRY',
  'INVALID_RANGE',
  'INVALID_PARAMETER',
  'MALFORMED_REQUEST',
]);

export function problemMessage(code: string, status: number, detail: string | null): string {
  if (DETAIL_IS_USER_FACING.has(code) && detail) {
    return detail;
  }
  const friendly = FRIENDLY_MESSAGES[code];
  if (friendly) {
    return friendly;
  }
  return status >= 500
    ? 'Something went wrong on our side. Please try again later.'
    : 'The request could not be completed.';
}
