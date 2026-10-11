import { AbstractControl, ValidationErrors } from '@angular/forms';

export const MAX_URL_LENGTH = 2048;

/**
 * Friendly client-side check for the create form. The server is always the authority (it also rejects
 * private hosts, numeric-IP tricks, etc.); this only catches the obvious mistakes before a round trip.
 * Returns an error message, or null when the value looks acceptable.
 */
export function urlProblem(value: string | null | undefined): string | null {
  const raw = (value ?? '').trim();
  if (raw === '') {
    return 'Enter a URL.';
  }
  if (raw.length > MAX_URL_LENGTH) {
    return `The URL is too long (maximum ${MAX_URL_LENGTH} characters).`;
  }
  if (/\s/.test(raw)) {
    return 'The URL must not contain spaces.';
  }
  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return 'Enter a full URL including http:// or https://.';
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return 'Only http:// and https:// URLs are supported.';
  }
  if (parsed.username !== '' || parsed.password !== '') {
    return 'URLs containing a username or password are not allowed.';
  }
  if (parsed.hostname === '') {
    return 'The URL needs a host name.';
  }
  return null;
}

/** Reactive-forms validator wrapper around {@link urlProblem}. */
export function urlValidator(control: AbstractControl): ValidationErrors | null {
  const problem = urlProblem(control.value as string | null);
  return problem ? { url: problem } : null;
}

/**
 * Optional expiry from a `datetime-local` input (local time, no zone). Returns an error message when the
 * value is not a valid future instant. Empty means "never expires".
 */
export function expiryProblem(value: string | null | undefined, now: Date = new Date()): string | null {
  if (!value) {
    return null;
  }
  const when = new Date(value);
  if (Number.isNaN(when.getTime())) {
    return 'Enter a valid date and time.';
  }
  if (when.getTime() <= now.getTime()) {
    return 'The expiration must be in the future.';
  }
  return null;
}

export function expiryValidator(control: AbstractControl): ValidationErrors | null {
  const problem = expiryProblem(control.value as string | null);
  return problem ? { expiry: problem } : null;
}

/** Converts a `datetime-local` value (browser local time) to the ISO-8601 UTC instant the API expects. */
export function toIsoInstant(localDateTime: string): string {
  return new Date(localDateTime).toISOString();
}
