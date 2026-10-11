import { ApiProblem } from './api-problem';

/** What a page that loads one thing from the API can be showing. */
export type LoadState<T> =
  | { readonly status: 'loading' }
  | { readonly status: 'error'; readonly problem: ApiProblem }
  | { readonly status: 'loaded'; readonly data: T };

export const LOADING = { status: 'loading' } as const;
