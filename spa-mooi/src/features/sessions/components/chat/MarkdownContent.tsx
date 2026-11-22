import ReactMarkdown, { type ExtraProps } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { CopyButton } from '@/shared/components/CopyButton';

type HastNode = NonNullable<ExtraProps['node']> | { type: string; value?: string; children?: HastNode[] };

/** Plain text of a rendered node, as the user would select it. */
const textOf = (node: HastNode | undefined): string => {
  if (!node) return '';
  if ('value' in node && typeof node.value === 'string') return node.value;
  return 'children' in node && node.children ? (node.children as HastNode[]).map(textOf).join('') : '';
};

/** Provider text supports Markdown, with no raw HTML or automatic remote image loads. */
export default function MarkdownContent({ text }: { text: string }) {
  return <div className="[&_p]:my-3 [&_pre]:overflow-auto [&_pre]:rounded-xl [&_pre]:border [&_pre]:border-line [&_pre]:bg-surface-2 [&_pre]:p-4 [&_pre]:pr-12 [&_code]:font-mono [&_code]:text-[0.85em] [&_ul]:my-3 [&_ul]:list-disc [&_ul]:pl-6 [&_ol]:my-3 [&_ol]:list-decimal [&_ol]:pl-6 [&_li]:my-1 [&_h1]:mt-6 [&_h1]:text-2xl [&_h1]:font-semibold [&_h2]:mt-5 [&_h2]:text-xl [&_h2]:font-semibold [&_h3]:mt-4 [&_h3]:font-semibold [&_a]:text-brand [&_a]:underline [&_blockquote]:border-l-2 [&_blockquote]:border-brand/40 [&_blockquote]:pl-4 [&_blockquote]:text-ink-muted [&_table]:my-4 [&_table]:block [&_table]:overflow-auto [&_td]:border [&_td]:border-line [&_td]:p-2 [&_th]:border [&_th]:border-line [&_th]:bg-surface-2 [&_th]:p-2 [&_hr]:my-6 [&_hr]:border-line [&>:first-child]:mt-0 [&>:last-child]:mb-0"><ReactMarkdown remarkPlugins={[remarkGfm]} components={{
    a: ({ children, ...props }) => <a {...props} target="_blank" rel="noopener noreferrer">{children}</a>,
    img: ({ alt }) => <span>{alt ? `[Image: ${alt}]` : '[Image]'}</span>,
    // Blocks meant to be copied (prompts, commands, snippets) get a copy button that stays put while the block scrolls.
    pre: ({ node, children, ...props }) => <div className="relative my-4">
      <pre {...props}>{children}</pre>
      <CopyButton value={textOf(node).replace(/\n$/, '')} label="Copy" className="absolute top-2 right-2 bg-surface-2/90" />
    </div>,
  }}>{text}</ReactMarkdown></div>;
}
