import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import App from './App.jsx'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
)

// Registers the app-shell service worker (public/sw.js) so re-opening the
// app while offline loads the cached React shell instead of the browser's
// native "Webpage not available" error — see sw.js for the full reasoning.
// Skipped in dev (vite dev server) since there's nothing built to cache
// yet and it would only get in the way of hot reload.
if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // Non-fatal — app still works online, just without offline-shell caching.
    })
  })
}
