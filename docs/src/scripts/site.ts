// Theme toggle, table-of-contents scroll spy and code copy buttons, shared by every page.
type Theme = 'dark' | 'light';
const STORAGE_KEY = 'vanadium-docs-theme';
const root = document.documentElement;

function applyTheme(theme: Theme, persist: boolean): void {
  root.dataset.theme = theme;
  if (persist) {
    try {
      localStorage.setItem(STORAGE_KEY, theme);
    } catch {
      // Private windows may refuse storage; the theme still applies for this page.
    }
  }
  const button = document.getElementById('themeBtn');
  if (button) {
    button.textContent = theme === 'dark' ? 'Light theme' : 'Dark theme';
    button.setAttribute('aria-pressed', String(theme === 'light'));
  }
  window.dispatchEvent(new CustomEvent('vanadium-theme', { detail: { theme } }));
}

applyTheme(root.dataset.theme === 'light' ? 'light' : 'dark', false);
document.getElementById('themeBtn')?.addEventListener('click', () => {
  applyTheme(root.dataset.theme === 'dark' ? 'light' : 'dark', true);
});

const tocLinks = [...document.querySelectorAll<HTMLAnchorElement>('#tocList a')];
if (tocLinks.length > 0) {
  const observer = new IntersectionObserver(
    (entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        const href = `#${entry.target.id}`;
        for (const link of tocLinks) link.classList.toggle('active', link.getAttribute('href') === href);
      }
    },
    { rootMargin: '-14% 0px -72% 0px' },
  );
  for (const link of tocLinks) {
    const target = document.getElementById(decodeURIComponent(link.hash.slice(1)));
    if (target) observer.observe(target);
  }
}

for (const button of document.querySelectorAll<HTMLButtonElement>('[data-copy]')) {
  button.addEventListener('click', async () => {
    const code = button.closest('figure.code')?.querySelector('pre')?.textContent ?? '';
    try {
      await navigator.clipboard.writeText(code);
      button.textContent = 'Copied';
    } catch {
      button.textContent = 'Copy failed';
    }
    setTimeout(() => {
      button.textContent = 'Copy';
    }, 1400);
  });
}
