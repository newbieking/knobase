import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { BookOpen, Code2, Building2, Headphones, Megaphone, Users, X, Check, ChevronDown, ArrowUpRight, FileText, Search, Loader2 } from 'lucide-react';
import type { KnowledgeBase, Bootstrap } from './types';
import { number } from './api';

export function Brand({ small = false }: { small?: boolean }) {
  return <div className={`brand ${small ? 'small' : ''}`}><img src="/favicon.svg" alt="" /><div><strong>知序 <span>Knobase</span></strong>{!small && <p>让知识，井然有序</p>}</div></div>;
}
const icons = { book: BookOpen, code: Code2, building: Building2, headphones: Headphones, megaphone: Megaphone, users: Users };
export function KBIcon({ kb, size = 21 }: { kb: Pick<KnowledgeBase, 'icon' | 'color'>; size?: number }) {
  const Icon = icons[kb.icon as keyof typeof icons] || BookOpen;
  const colors: Record<string, string> = { orange: '#e48a4b', blue: '#598fdd', purple: '#9680d3', green: '#51a692', pink: '#d77d9c', amber: '#cc9d49' };
  const color = colors[kb.color] || (/^#[0-9a-fA-F]{6}$/.test(kb.color) ? kb.color : '#e48a4b');
  return <span className="kb-icon" style={{ color, background: `${color}16` }}><Icon size={size} strokeWidth={1.7} /></span>;
}
export function FileIcon({ type }: { type: string }) { return <span className={`file-icon file-${type}`}><FileText size={21} strokeWidth={1.5} /><span>{type.toUpperCase()}</span></span>; }
export function Empty({ title, description, action }: { title: string; description: string; action?: ReactNode }) { return <div className="empty"><div className="empty-icon"><Search size={28} /></div><h3>{title}</h3><p>{description}</p>{action}</div>; }
export function Spinner({ label = '正在加载…' }: { label?: string }) { return <div className="loading"><Loader2 size={24} className="spin" /><span>{label}</span></div>; }
export function Badge({ children, tone = 'green' }: { children: ReactNode; tone?: string }) { return <span className={`badge badge-${tone}`}><i />{children}</span>; }
export function Modal({ title, subtitle, children, onClose, wide = false }: { title: string; subtitle?: string; children: ReactNode; onClose: () => void; wide?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement;
    const before = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const timer = setTimeout(() => ref.current?.querySelector<HTMLElement>('input, select, textarea, button')?.focus(), 50);
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      if (e.key === 'Tab') {
        const items = ref.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),a[href],[tabindex="0"]');
        if (!items?.length) return;
        const first = items[0], last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
        else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
      }
    };
    window.addEventListener('keydown', handler);
    return () => { clearTimeout(timer); document.body.style.overflow = before; window.removeEventListener('keydown', handler); previous?.focus(); };
  }, [onClose]);
  return <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}><div ref={ref} role="dialog" aria-modal="true" aria-label={title} className={`modal ${wide ? 'modal-wide' : ''}`}><div className="modal-head"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div><button className="icon-button" aria-label="关闭弹窗" onClick={onClose}><X size={20} /></button></div>{children}</div></div>;
}
export function Toggle({ value, onChange, label }: { value: boolean; onChange: (value: boolean) => void; label: string }) { return <button type="button" role="switch" aria-checked={value} aria-label={label} className={`toggle ${value ? 'on' : ''}`} onClick={() => onChange(!value)}><span>{value && <Check size={10} />}</span></button>; }
export function SectionHeading({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) { return <div className="section-heading"><div><h2>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{action}</div>; }
export function PageHeading({ eyebrow, title, description, action }: { eyebrow?: string; title: string; description: string; action?: ReactNode }) { return <div className="page-heading"><div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1>{title}</h1><p>{description}</p></div>{action && <div className="heading-actions">{action}</div>}</div>; }

export function TrendChart({ trend, expanded = false }: { trend: Bootstrap['stats']['trend']; expanded?: boolean }) {
  const [period, setPeriod] = useState('7');
  const [metric, setMetric] = useState<'queries' | 'tokens'>('queries');
  const [hovered, setHovered] = useState<number | null>(null);
  const values = trend.slice(-Number(period));
  const max = Math.max(100, ...values.map(p => p[metric])) * 1.25;
  const w = 720, h = expanded ? 275 : 205, left = 45, right = 12, top = 16, bottom = 31;
  const getX = (i: number) => left + i * (w - left - right) / Math.max(1, values.length - 1);
  const getY = (v: number) => top + (1 - v / max) * (h - top - bottom);
  const points = values.map((p, i) => [getX(i), getY(p[metric])]);
  let path = points.length ? `M${points[0][0]},${points[0][1]}` : '';
  for (let i = 1; i < points.length; i++) { const mid = (points[i - 1][0] + points[i][0]) / 2; path += ` C${mid},${points[i - 1][1]} ${mid},${points[i][1]} ${points[i][0]},${points[i][1]}`; }
  const area = points.length ? `${path} L${points[points.length - 1][0]},${h - bottom} L${left},${h - bottom} Z` : '';
  return <div className="panel trend-panel"><div className="panel-heading"><div><h2>知识使用趋势 <span className="muted-label">DEMO DATA</span></h2><p>每一次提问，都是知识价值的释放</p></div><select className="small-select" aria-label="趋势时间范围" value={period} onChange={e => { setPeriod(e.target.value); setHovered(null); }}><option value="7">近 7 天</option><option value="14">近 14 天</option><option value="30">近 30 天</option></select></div><div className="chart-toolbar"><div className="chart-tabs"><button className={metric === 'queries' ? 'selected' : ''} onClick={() => setMetric('queries')}>问答量</button><button className={metric === 'tokens' ? 'selected' : ''} onClick={() => setMetric('tokens')}>Token 消耗</button></div><span className="chart-legend"><i />{metric === 'queries' ? '问答次数' : 'Token 数量'}<span className="legend-total">{number(values.reduce((a, b) => a + b[metric], 0))}</span></span></div><div className="chart-container"><svg viewBox={`0 0 ${w} ${h}`} role="img" aria-label={`近 ${period} 天${metric === 'queries' ? '问答量' : 'Token 消耗'}趋势图`} onMouseLeave={() => setHovered(null)}><defs><linearGradient id={`chart-fill-${expanded ? 'large' : 'small'}`} x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stopColor="#ef9069" stopOpacity=".22" /><stop offset="100%" stopColor="#ef9069" stopOpacity=".015" /></linearGradient></defs>{[0, 1, 2, 3, 4].map(t => <g key={t}><line x1={left} x2={w - right} y1={getY(max * t / 4)} y2={getY(max * t / 4)} stroke="#eceef1" strokeDasharray="4 4" /><text x={left - 12} y={getY(max * t / 4) + 4} textAnchor="end" className="chart-label">{metric === 'tokens' ? `${Math.round(max * t / 4000)}k` : Math.round(max * t / 4).toLocaleString()}</text></g>)}<path d={area} fill={`url(#chart-fill-${expanded ? 'large' : 'small'})`} /><path d={path} fill="none" stroke="#ed8b5e" strokeWidth="2.6" strokeLinecap="round" />{values.map((v, i) => <g key={v.date}>{(values.length <= 7 || i % Math.ceil(values.length / 7) === 0 || i === values.length - 1) && <text x={getX(i)} y={h - 5} textAnchor="middle" className="chart-label">{v.date.slice(5).replace('-', '/')}</text>}<rect x={getX(i) - (w - left) / values.length / 2} y="0" width={(w - left) / values.length} height={h - bottom} fill="transparent" onMouseEnter={() => setHovered(i)} /></g>)}{hovered !== null && points[hovered] && <g pointerEvents="none"><line x1={getX(hovered)} x2={getX(hovered)} y1="4" y2={h - bottom} stroke="#ed8b5e" strokeDasharray="4 4" /><circle cx={getX(hovered)} cy={points[hovered][1]} r="4.5" fill="#ed8b5e" stroke="white" strokeWidth="2" /><rect x={Math.min(w - 134, Math.max(left, getX(hovered) - 64))} y="0" width="124" height="29" rx="6" fill="#30343b" /><text x={Math.min(w - 72, Math.max(left + 62, getX(hovered) - 2))} y="19" textAnchor="middle" fill="white" fontSize="11">{values[hovered].date.slice(5)} · {number(values[hovered][metric])}</text></g>}</svg></div><div className="chart-foot"><span>数据更新于刚刚 <span className="quiet">· 含预置演示历史</span></span><span><ArrowUpRight size={13} /> 让数据驱动知识运营</span></div></div>;
}
export function SelectArrow() { return <ChevronDown size={14} />; }
