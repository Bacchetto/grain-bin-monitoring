/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // The dev server watches the source files to reload the page on a
    // change. By default it asks the operating system to report changes, and
    // on a mapped network drive -- where this repository lives on the
    // development machine -- that call fails and the dev server crashes on
    // start ("UNKNOWN: unknown error, watch"). Polling asks "has anything
    // changed?" on a timer instead: slightly more CPU, but it works on any
    // drive. Set VITE_NATIVE_WATCH=true on a local disk to go back to the
    // faster native watcher.
    watch: process.env.VITE_NATIVE_WATCH === 'true' ? {} : { usePolling: true, interval: 300 },
  },
  // Vitest reads its settings from here, so tests compile exactly as the app
  // does. jsdom is a simulated browser DOM running in Node: components render
  // into it without a real browser.
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
