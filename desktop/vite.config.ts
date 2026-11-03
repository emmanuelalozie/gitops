import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Tauri serves the dev build on a fixed port and expects the same port every time.
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: { port: 1420, strictPort: true, host: "127.0.0.1", watch: { ignored: ["**/src-tauri/**"] } },
  build: { target: "es2022", sourcemap: false },
  test: { environment: "jsdom", globals: true },
});
