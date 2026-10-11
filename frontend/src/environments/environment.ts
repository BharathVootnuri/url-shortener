// Production build (`ng build`). An empty base URL means "same origin as the page", i.e. the app is served
// behind a reverse proxy that routes /api/** to the backend. Change it at build time if the API lives
// elsewhere (the backend must then allow this origin in app.cors.allowed-origins).
export const environment = {
  apiBaseUrl: '',
  production: true,
};
