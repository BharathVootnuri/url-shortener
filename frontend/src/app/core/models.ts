// Hand-written from docs/openapi.json (the committed contract). Instants are ISO-8601 strings.

export type LinkStatus = 'ACTIVE' | 'EXPIRED';
export type Granularity = 'HOUR' | 'DAY';

export interface CreateLinkRequest {
  url: string;
  expiresAt?: string | null;
}

export interface LinkResponse {
  code: string;
  shortUrl: string;
  originalUrl: string;
  createdAt: string;
  expiresAt: string | null;
}

export interface LinkDetails {
  code: string;
  shortUrl: string;
  originalUrl: string;
  createdAt: string;
  expiresAt: string | null;
  status: LinkStatus;
  totalClicks: number;
}

export interface ClickBucket {
  bucketStart: string;
  clicks: number;
}

export interface ReferrerStat {
  referrer: string;
  clicks: number;
}

export interface Analytics {
  code: string;
  totalClicks: number;
  clicksInRange: number;
  from: string;
  to: string;
  granularity: Granularity;
  series: ClickBucket[];
  topReferrers: ReferrerStat[];
}

export interface AnalyticsQuery {
  from?: string;
  to?: string;
  granularity?: Granularity;
}
