import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { App } from './App'
import { AuthProvider } from './auth/AuthProvider'
import './index.css'

// The `!` asserts to the compiler that the element exists -- index.html
// always has <div id="root">. StrictMode renders components twice in
// development to flush out side effects in the wrong place; it does nothing
// in a production build.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
