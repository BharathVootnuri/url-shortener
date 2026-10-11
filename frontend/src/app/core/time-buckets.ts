import { ClickBucket, Granularity } from './models';

const STEP_MS: Record<Granularity, number> = {
  HOUR: 3_600_000,
  DAY: 86_400_000,
};

/** Start of the UTC bucket containing the instant (same alignment as the backend). */
export function bucketStartMs(instantMs: number, granularity: Granularity): number {
  const step = STEP_MS[granularity];
  return Math.floor(instantMs / step) * step;
}

/**
 * The API returns a sparse series (only buckets that had clicks). Charts need every bucket in the range,
 * so this fills the gaps with zeros. Buckets are those overlapping [from, to).
 */
export function fillBuckets(
  series: readonly ClickBucket[],
  from: string,
  to: string,
  granularity: Granularity,
): ClickBucket[] {
  const fromMs = Date.parse(from);
  const toMs = Date.parse(to);
  if (Number.isNaN(fromMs) || Number.isNaN(toMs) || toMs <= fromMs) {
    return [...series];
  }

  const step = STEP_MS[granularity];
  const clicksByStart = new Map<number, number>();
  for (const bucket of series) {
    const start = bucketStartMs(Date.parse(bucket.bucketStart), granularity);
    clicksByStart.set(start, (clicksByStart.get(start) ?? 0) + bucket.clicks);
  }

  const result: ClickBucket[] = [];
  // `to` is exclusive: the last bucket is the one containing the final instant before `to`.
  const last = bucketStartMs(toMs - 1, granularity);
  for (let start = bucketStartMs(fromMs, granularity); start <= last; start += step) {
    result.push({ bucketStart: new Date(start).toISOString(), clicks: clicksByStart.get(start) ?? 0 });
  }
  return result;
}
