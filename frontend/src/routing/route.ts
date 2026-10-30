const ROUTES = {
  home: "/",
  laboratory: "/laboratory",
} as const;

export type AppRoute = keyof typeof ROUTES;

export function routeFromPathname(pathname: string): AppRoute {
  const normalized = pathname.length > 1 && pathname.endsWith("/") ? pathname.slice(0, -1) : pathname;
  if (normalized === ROUTES.laboratory) return "laboratory";
  return "home";
}

export function hrefFor(route: AppRoute): string {
  return ROUTES[route];
}

export function navigate(pathname: string): void {
  if (window.location.pathname === pathname) return;
  window.history.pushState(null, "", pathname);
  window.dispatchEvent(new PopStateEvent("popstate"));
}
