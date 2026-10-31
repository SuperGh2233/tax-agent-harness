import * as Dialog from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { useRef } from 'react'

// shadcn-style, locally reviewable primitives; Radix owns focus trapping and Escape/restore.
export function Button({className = '', variant = 'secondary', ...props}: ButtonHTMLAttributes<HTMLButtonElement> & {variant?: 'primary' | 'secondary' | 'ghost'}) {
  return <button className={`button ${variant} ${className}`} {...props}/>
}
export function Modal({open, onOpenChange, title, description, children, drawer = false}: {
  open: boolean; onOpenChange: (open: boolean) => void; title: string; description: string; children: ReactNode; drawer?: boolean
}) {
  const returnFocus = useRef<HTMLElement | null>(null)
  return <Dialog.Root open={open} onOpenChange={onOpenChange}><Dialog.Portal>
    {/* CSS scroll lock avoids react-remove-scroll's injected stylesheet under Spring's strict CSP. */}
    <div className="overlay" aria-hidden="true"/>
    <Dialog.Content className={drawer ? 'dialog drawer' : 'dialog'} onOpenAutoFocus={() => { returnFocus.current = document.activeElement as HTMLElement }} onCloseAutoFocus={event => { event.preventDefault(); returnFocus.current?.focus() }}>
      <div className="section-heading"><Dialog.Title>{title}</Dialog.Title><Dialog.Close asChild><Button variant="ghost" aria-label="关闭"><X size={18}/></Button></Dialog.Close></div>
      <Dialog.Description className="muted">{description}</Dialog.Description>{children}
    </Dialog.Content>
  </Dialog.Portal></Dialog.Root>
}
