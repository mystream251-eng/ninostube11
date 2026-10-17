const CACHE_NAME = 'ninostube-v2';
const PRECACHE_ASSETS = [
  './',
  './index.html',
  './manifest.json',
  './icon.svg'
];

self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => {
      return cache.addAll(PRECACHE_ASSETS);
    }).then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys => {
      return Promise.all(
        keys.map(k => {
          if (k !== CACHE_NAME) {
            return caches.delete(k);
          }
        })
      );
    }).then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const req = event.request;
  const url = new URL(req.url);

  // Solo gestionar peticiones del mismo origen; los servicios externos (YouTube, APIs, descargas) no se tocan
  if (url.origin !== self.location.origin) {
    return;
  }

  // Para navegaciones (req.mode === 'navigate'): red primero con límite de 3 segundos
  if (req.mode === 'navigate') {
    event.respondWith(
      (async () => {
        const timeoutPromise = new Promise((_, reject) => {
          setTimeout(() => reject(new Error('timeout')), 3000);
        });

        try {
          const networkResp = await Promise.race([
            fetch(req),
            timeoutPromise
          ]);
          if (networkResp && networkResp.status === 200) {
            const respToCache = networkResp.clone();
            caches.open(CACHE_NAME).then(cache => {
              cache.put(req, respToCache);
            });
          }
          return networkResp;
        } catch (e) {
          const cached = await caches.match('./index.html');
          return cached || (await caches.match('./'));
        }
      })()
    );
    return;
  }

  // Stale-while-revalidate para los demás archivos del mismo origen
  event.respondWith(
    caches.match(req).then(cachedResp => {
      const fetchPromise = fetch(req).then(networkResp => {
        if (networkResp && networkResp.status === 200 && networkResp.type === 'basic') {
          const respToCache = networkResp.clone();
          caches.open(CACHE_NAME).then(cache => {
            cache.put(req, respToCache);
          });
        }
        return networkResp;
      }).catch(() => {});

      return cachedResp || fetchPromise;
    })
  );
});
