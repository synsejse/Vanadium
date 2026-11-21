/** Site-relative link to a docs page, honouring the configured base path. */
export function pageHref(id: string, hash = ''): string {
  const base = import.meta.env.BASE_URL.endsWith('/') ? import.meta.env.BASE_URL : `${import.meta.env.BASE_URL}/`;
  return `${base}${id === 'overview' ? '' : id}${hash}`;
}
