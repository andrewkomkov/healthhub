import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './core/m3e/base.css'

const container = document.getElementById('root')
if (!container) throw new Error('Missing #root')

createRoot(container).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
