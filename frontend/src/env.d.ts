/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_APP_BASE_PATH?: string
  readonly VITE_EDUTWIN_MODE?: 'server' | 'showcase'
  readonly VITE_EDUTWIN_DEMO_PROFILE?: 'showcase' | 'research'
}
