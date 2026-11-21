// T50 motion helpers. Presentation only: no state, no requests, nothing a test or the write lock depends on.
// Both write CSS custom properties through the CSSOM (allowed under the strict `style-src 'self'` CSP);
// prefers-reduced-motion is handled in style.css, which turns every animation and transition off.
import { useEffect, useLayoutEffect } from 'react'
import type { RefObject } from 'react'

/** Slides the nav's highlight pill under the current page's button; hidden when the page is not in the nav. */
export function useNavIndicator(nav: RefObject<HTMLElement | null>, page: string) {
  useLayoutEffect(() => {
    const element = nav.current
    if (!element) return
    const place = () => {
      const active = element.querySelector<HTMLElement>('button[aria-current]')
      element.style.setProperty('--ind-o', active ? '1' : '0')
      if (active) {
        element.style.setProperty('--ind-x', `${active.offsetLeft}px`)
        element.style.setProperty('--ind-y', `${active.offsetTop}px`)
        element.style.setProperty('--ind-w', `${active.offsetWidth}px`)
        element.style.setProperty('--ind-h', `${active.offsetHeight}px`)
      }
    }
    place()
    // Transitions start after the first placement, so the pill does not slide in from the left on load.
    const frame = requestAnimationFrame(() => element.setAttribute('data-ready', 'true'))
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(place)
    observer?.observe(element)
    return () => { cancelAnimationFrame(frame); observer?.disconnect() }
  }, [nav, page])
}

const SPOTLIGHT = '.suggestions button, .agent-card, .upload-drop'

/** A soft light that follows the pointer across cards (see the ::before rules in style.css). */
export function useSpotlight() {
  useEffect(() => {
    const move = (event: PointerEvent) => {
      const target = event.target instanceof Element ? event.target.closest<HTMLElement>(SPOTLIGHT) : null
      if (!target) return
      const box = target.getBoundingClientRect()
      target.style.setProperty('--mx', `${event.clientX - box.left}px`)
      target.style.setProperty('--my', `${event.clientY - box.top}px`)
    }
    document.addEventListener('pointermove', move, {passive:true})
    return () => document.removeEventListener('pointermove', move)
  }, [])
}
