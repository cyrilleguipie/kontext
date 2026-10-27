# Kontext Web UI

Kontext chat interface built with Vue 3, TypeScript, and Vite. It streams answers from `POST /api/chat` and displays linked source passages returned by the backend.

Run locally with `npm install` and `npm run dev`. Vite proxies `/api` to `http://localhost:8080`; production images are served by Nginx and proxy API requests to the backend service.
