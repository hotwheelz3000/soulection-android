// Runs on every page load (start and finish).
// Same idea as WKUserScript in the iOS apps.
// Guard so it only sets things up once per page.
(function () {
  if (window.__injected) return;
  window.__injected = true;

  // Example: hide elements by CSS
  var style = document.createElement('style');
  style.textContent = [
    '/* .cookie-banner { display: none !important; } */'
  ].join('\n');
  (document.head || document.documentElement).appendChild(style);

  // Example: send a message to the app (shows a toast)
  // window.Android.postMessage('hello from the page');
})();
