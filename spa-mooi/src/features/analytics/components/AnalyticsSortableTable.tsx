import { useMemo, useState } from 'react';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { cn } from '@/shared/utils/cn';
import { Button } from '@/shared/components/Button';

export interface AnalyticsTableColumn<Row> {
  id: string;
  label: string;
  /** Sort value; strings sort alphabetically, numbers numerically. */
  value: (row: Row) => string | number;
  render: (row: Row) => string;
  /** Sorts ascending first (a position, where lower is better). */
  ascending?: boolean;
}

interface AnalyticsSortableTableProps<Row> {
  rows: readonly Row[];
  /** The first column is the row's name; the others are right-aligned numbers. */
  columns: AnalyticsTableColumn<Row>[];
  rowKey: (row: Row) => string;
  defaultSort: string;
  emptyText: string;
  /** Optional client-side expansion of already fetched rows, after sorting the full set. */
  batchSize?: number;
}

/** A compact table whose headers sort; scrolls sideways on narrow screens instead of squeezing numbers. */
export const AnalyticsSortableTable = <Row,>({ rows, columns, rowKey, defaultSort, emptyText, batchSize }: AnalyticsSortableTableProps<Row>) => {
  const [limit, setLimit] = useState(batchSize ?? Infinity);
  const [sort, setSort] = useState<{ id: string; desc: boolean }>(() => ({
    id: defaultSort,
    desc: !columns.find((column) => column.id === defaultSort)?.ascending,
  }));
  const column = columns.find((item) => item.id === sort.id) ?? columns[0];

  const sorted = useMemo(() => {
    const direction = sort.desc ? -1 : 1;
    return [...rows].sort((a, b) => {
      const left = column.value(a);
      const right = column.value(b);
      return (typeof left === 'string' && typeof right === 'string' ? left.localeCompare(right) : Number(left) - Number(right)) * direction;
    });
  }, [rows, column, sort.desc]);

  if (rows.length === 0) return <div><p className="py-4 text-center text-[13px] text-ink-muted">{emptyText}</p>{batchSize ? <p role="status" className="text-xs text-ink-subtle">Showing 0 of 0 fetched queries</p> : null}</div>;

  const choose = (item: AnalyticsTableColumn<Row>) =>
    setSort((current) => (current.id === item.id ? { id: item.id, desc: !current.desc } : { id: item.id, desc: !item.ascending }));

  return (
    <>
    <div className="-mx-1 overflow-x-auto px-1">
      <table className="w-full min-w-[26rem] border-collapse text-[13px]">
        <thead>
          <tr>
            {columns.map((item, index) => {
              const active = item.id === sort.id;
              const Icon = sort.desc ? ArrowDown : ArrowUp;
              return (
                <th key={item.id} scope="col" aria-sort={active ? (sort.desc ? 'descending' : 'ascending') : 'none'}
                  className={cn('border-b border-line px-2 py-1.5 text-[11px] font-medium whitespace-nowrap text-ink-subtle', index === 0 ? 'text-left' : 'text-right')}>
                  <button type="button" onClick={() => choose(item)}
                    className={cn('inline-flex items-center gap-1 rounded-chip hover:text-ink focus-visible:outline-2 focus-visible:outline-brand', active && 'text-ink')}>
                    {item.label}
                    {active ? <Icon aria-hidden className="size-3" /> : null}
                  </button>
                </th>
              );
            })}
          </tr>
        </thead>
        <tbody>
          {sorted.slice(0, limit).map((row) => (
            <tr key={rowKey(row)} className="border-b border-line/60 last:border-0">
              {columns.map((item, index) => (
                <td key={item.id} className={cn('px-2 py-1.5', index === 0 ? 'max-w-[16rem] min-w-0 truncate font-mono text-[12px]' : 'text-right tabular-nums')}
                  title={index === 0 ? item.render(row) : undefined}>
                  {item.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
    {batchSize ? <div className="flex flex-wrap items-center justify-between gap-2">
      <p role="status" aria-live="polite" aria-atomic="true" className="text-xs text-ink-subtle">Showing {Math.min(limit, rows.length)} of {rows.length} fetched queries</p>
      {limit < rows.length ? <Button variant="secondary" size="sm" onClick={() => setLimit((current) => current + batchSize)}>Load more</Button> : null}
    </div> : null}
    </>
  );
};
