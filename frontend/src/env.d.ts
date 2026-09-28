// Declares the environment variables the app reads through import.meta.env,
// so the compiler knows VITE_API_URL exists and may be undefined. Without
// this, TypeScript would type it as `any` and catch nothing.

interface ImportMetaEnv {
  /** The API's base URL. Defaults to http://localhost:8080 (see api/client.ts). */
  readonly VITE_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
