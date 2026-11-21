(() => {
  const root = document.documentElement;
  const storageKey = 'vanadium-docs-theme';

  function readSavedTheme() {
    try {
      const saved = localStorage.getItem(storageKey);
      if (saved === 'dark' || saved === 'light') return saved;
    } catch (_) {}
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) {
      return 'light';
    }
    return 'dark';
  }

  function applyTheme(theme, persist) {
    root.dataset.theme = theme;
    if (persist) {
      try { localStorage.setItem(storageKey, theme); } catch (_) {}
    }
    const btn = document.getElementById('themeBtn');
    if (btn) {
      btn.textContent = theme === 'dark' ? 'Light theme' : 'Dark theme';
      btn.setAttribute('aria-pressed', theme === 'light' ? 'true' : 'false');
    }
    document.querySelectorAll('img[data-dark-src][data-light-src]').forEach(img => {
      img.src = theme === 'light' ? img.dataset.lightSrc : img.dataset.darkSrc;
    });
    window.dispatchEvent(new CustomEvent('vanadium-theme', { detail: { theme } }));
  }

  applyTheme(readSavedTheme(), false);

  const btn = document.getElementById('themeBtn');
  if (btn) {
    btn.addEventListener('click', () => {
      applyTheme(root.dataset.theme === 'dark' ? 'light' : 'dark', true);
    });
  }

  // Scroll-spy for sticky TOC
  const tocLinks = [...document.querySelectorAll('#tocList a')];
  if (tocLinks.length > 0 && 'IntersectionObserver' in window) {
    const targets = tocLinks
      .map(a => document.querySelector(a.getAttribute('href')))
      .filter(Boolean);
    const observer = new IntersectionObserver(entries => {
      for (const entry of entries) {
        if (entry.isIntersecting) {
          const href = '#' + entry.target.id;
          tocLinks.forEach(a => a.classList.toggle('active', a.getAttribute('href') === href));
        }
      }
    }, { rootMargin: '-14% 0px -72% 0px' });
    targets.forEach(t => observer.observe(t));
  }

  // Copy buttons on standalone code blocks
  document.querySelectorAll('[data-copy-code]').forEach(copyBtn => {
    copyBtn.addEventListener('click', async () => {
      const pre = copyBtn.closest('.code-block')?.querySelector('pre');
      if (!pre) return;
      try {
        await navigator.clipboard.writeText(pre.textContent);
        copyBtn.textContent = 'Copied';
        setTimeout(() => { copyBtn.textContent = 'Copy'; }, 1400);
      } catch (_) {
        copyBtn.textContent = 'Copy failed';
        setTimeout(() => { copyBtn.textContent = 'Copy'; }, 1400);
      }
    });
  });
})();
