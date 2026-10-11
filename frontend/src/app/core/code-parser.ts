/**
 * Accepts either a bare short code ("aB3dE9x") or a full short URL ("http://host/aB3dE9x") and returns the
 * code, or null when the input cannot be a code.
 */
export function extractCode(input: string | null | undefined): string | null {
  const raw = (input ?? '').trim();
  if (raw === '') {
    return null;
  }

  let candidate = raw;
  if (/^https?:\/\//i.test(raw)) {
    try {
      const segments = new URL(raw).pathname.split('/').filter((s) => s !== '');
      candidate = segments.length > 0 ? segments[segments.length - 1] : '';
    } catch {
      return null;
    }
  }
  return /^[A-Za-z0-9]{1,16}$/.test(candidate) ? candidate : null;
}
