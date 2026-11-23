import { useState, type KeyboardEvent, type PointerEvent, type RefObject } from 'react';
import { clampPreviewShare, PREVIEW_SPLIT_DEFAULT, PREVIEW_SPLIT_MAX, PREVIEW_SPLIT_MIN } from '@/features/sessions/lib/previewSplit';
import { cn } from '@/shared/utils/cn';

const KEY_STEP = 0.05;

interface PreviewSplitHandleProps {
  /** The row holding both panes: the share is measured against its width. */
  containerRef: RefObject<HTMLDivElement | null>;
  /** Fraction of the row the docked preview takes, right of this handle. */
  share: number;
  onChange: (share: number) => void;
}

/** Drag, arrow keys or a double click (back to half) resize the docked preview. */
export const PreviewSplitHandle = ({ containerRef, share, onChange }: PreviewSplitHandleProps) => {
  const [dragging, setDragging] = useState(false);

  const shareAt = (clientX: number) => {
    const rect = containerRef.current?.getBoundingClientRect();
    return rect && rect.width > 0 ? clampPreviewShare((rect.right - clientX) / rect.width) : share;
  };

  const move = (event: PointerEvent<HTMLElement>) => {
    if (dragging) onChange(shareAt(event.clientX));
  };
  const stop = () => setDragging(false);

  const keydown = (event: KeyboardEvent<HTMLDivElement>) => {
    const next = event.key === 'ArrowLeft' ? share + KEY_STEP
      : event.key === 'ArrowRight' ? share - KEY_STEP
      : event.key === 'Home' ? PREVIEW_SPLIT_MAX
      : event.key === 'End' ? PREVIEW_SPLIT_MIN
      : null;
    if (next === null) return;
    event.preventDefault();
    onChange(clampPreviewShare(next));
  };

  return <>
    <div role="separator" aria-orientation="vertical" aria-label="Resize preview" tabIndex={0}
      aria-valuemin={Math.round(PREVIEW_SPLIT_MIN * 100)} aria-valuemax={Math.round(PREVIEW_SPLIT_MAX * 100)} aria-valuenow={Math.round(share * 100)}
      onPointerDown={(event) => { event.preventDefault(); setDragging(true); }}
      onDoubleClick={() => onChange(PREVIEW_SPLIT_DEFAULT)}
      onKeyDown={keydown}
      className="group/split relative z-10 -mx-1 w-2 shrink-0 cursor-col-resize touch-none focus-visible:outline-none">
      <span aria-hidden className={cn('absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-line transition-[background-color,width] duration-150 group-hover/split:w-0.5 group-hover/split:bg-brand group-focus-visible/split:w-0.5 group-focus-visible/split:bg-brand', dragging && 'w-0.5 bg-brand')} />
    </div>
    {/* While dragging, a cover keeps the preview iframe from swallowing the pointer. */}
    {dragging ? <div aria-hidden className="fixed inset-0 z-50 cursor-col-resize touch-none select-none"
      onPointerMove={move} onPointerUp={stop} onPointerCancel={stop} />: null}
  </>;
};
