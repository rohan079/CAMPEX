import type { ReactNode } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  ComposedChart,
  Legend,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { compactNum, formatDay, money, num } from "./format";
import type { CallsDay, LeadsDay, SalesDay } from "./types";

export const COLORS = {
  leads: "#3f6fa3",
  ads: "#b86114",
  agent: "#1d6b56",
  calls: "#c9bfa9",
  connected: "#1d6b56",
  grid: "#e7dfcd",
  axis: "#7a7264",
};

export const SERIES_COLORS = ["#b86114", "#1d6b56", "#3f6fa3", "#8d3b3b", "#7a5ea8", "#8a8a3a"];
export const OTHER_COLOR = "#c9bfa9";

const axisProps = { stroke: COLORS.axis, fontSize: 12, tickLine: false, axisLine: false } as const;

const tooltipProps = {
  contentStyle: {
    background: "#fffdf8",
    border: "1px solid #e4dac8",
    borderRadius: 12,
    boxShadow: "0 8px 24px rgba(23,36,28,0.12)",
    fontSize: 13,
  },
  labelStyle: { fontWeight: 600, marginBottom: 4 },
  cursor: { fill: "rgba(23,36,28,0.05)" },
} as const;

export function ChartCard({
  title,
  subtitle,
  hasData,
  emptyText = "Nothing recorded in this period yet.",
  children,
}: {
  title: string;
  subtitle?: string;
  hasData: boolean;
  emptyText?: string;
  children: ReactNode;
}) {
  return (
    <section className="card chart-card">
      <div className="card-head">
        <h3>{title}</h3>
        {subtitle && <p>{subtitle}</p>}
      </div>
      {hasData ? children : <p className="chart-empty">{emptyText}</p>}
    </section>
  );
}

const dayAxis = (
  <XAxis
    dataKey="day"
    tickFormatter={formatDay}
    interval="preserveStartEnd"
    minTickGap={28}
    {...axisProps}
  />
);

/** Leads (bars, left axis) against sales (line, right axis) for each day. */
export function LeadsAndSalesChart({ leads, sales }: { leads: LeadsDay[]; sales: SalesDay[] }) {
  const salesByDay = new Map(sales.map((s) => [s.day, s.sales]));
  const data = leads.map((d) => ({ day: d.day, leads: d.leads, sales: salesByDay.get(d.day) ?? 0 }));
  return (
    <ResponsiveContainer width="100%" height={270}>
      <ComposedChart data={data} margin={{ top: 8, right: 4, left: -12, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={COLORS.grid} />
        {dayAxis}
        <YAxis yAxisId="leads" allowDecimals={false} {...axisProps} />
        <YAxis yAxisId="sales" orientation="right" allowDecimals={false} {...axisProps} />
        <Tooltip {...tooltipProps} labelFormatter={(label) => formatDay(String(label))} />
        <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
        <Bar yAxisId="leads" dataKey="leads" name="Leads" fill={COLORS.leads} radius={[4, 4, 0, 0]} />
        <Line
          yAxisId="sales"
          dataKey="sales"
          name="Sales"
          stroke={COLORS.agent}
          strokeWidth={2.5}
          dot={{ r: 3 }}
          type="monotone"
        />
      </ComposedChart>
    </ResponsiveContainer>
  );
}

/** Revenue per day, split by channel. */
export function RevenueByChannelChart({
  ads,
  agent,
  currency,
}: {
  ads: SalesDay[];
  agent: SalesDay[];
  currency: string;
}) {
  const agentByDay = new Map(agent.map((s) => [s.day, s.revenue]));
  const data = ads.map((d) => ({ day: d.day, ads: d.revenue, agent: agentByDay.get(d.day) ?? 0 }));
  return (
    <ResponsiveContainer width="100%" height={270}>
      <BarChart data={data} margin={{ top: 8, right: 4, left: 0, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={COLORS.grid} />
        {dayAxis}
        <YAxis tickFormatter={(v) => money(Number(v), currency, true)} width={64} {...axisProps} />
        <Tooltip
          {...tooltipProps}
          labelFormatter={(label) => formatDay(String(label))}
          formatter={(value) => money(Number(value), currency)}
        />
        <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
        <Bar dataKey="ads" name="Ads" stackId="rev" fill={COLORS.ads} />
        <Bar dataKey="agent" name="Calling agent" stackId="rev" fill={COLORS.agent} radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}

/** Revenue for one channel per day. */
export function RevenueDayChart({
  data,
  color,
  name,
  currency,
}: {
  data: SalesDay[];
  color: string;
  name: string;
  currency: string;
}) {
  return (
    <ResponsiveContainer width="100%" height={250}>
      <BarChart data={data} margin={{ top: 8, right: 4, left: 0, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={COLORS.grid} />
        {dayAxis}
        <YAxis tickFormatter={(v) => money(Number(v), currency, true)} width={64} {...axisProps} />
        <Tooltip
          {...tooltipProps}
          labelFormatter={(label) => formatDay(String(label))}
          formatter={(value) => money(Number(value), currency)}
        />
        <Bar dataKey="revenue" name={name} fill={color} radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}

export function CallsChart({ data }: { data: CallsDay[] }) {
  const stacked = data.map((d) => ({
    day: d.day,
    answered: d.connected,
    unanswered: Math.max(d.calls - d.connected, 0),
  }));
  return (
    <ResponsiveContainer width="100%" height={250}>
      <BarChart data={stacked} margin={{ top: 8, right: 4, left: -12, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={COLORS.grid} />
        {dayAxis}
        <YAxis allowDecimals={false} {...axisProps} />
        <Tooltip {...tooltipProps} labelFormatter={(label) => formatDay(String(label))} />
        <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
        <Bar dataKey="answered" name="Answered" stackId="calls" fill={COLORS.connected} />
        <Bar dataKey="unanswered" name="Not answered" stackId="calls" fill={COLORS.calls} radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}

export interface BarDatum {
  label: string;
  value: number;
}

/** Horizontal ranking bars; used for ads by revenue and locations by reach. */
export function RankBars({
  data,
  color,
  name,
  format = num,
}: {
  data: BarDatum[];
  color: string;
  name: string;
  format?: (value: number) => string;
}) {
  const height = Math.max(150, data.length * 40 + 24);
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} layout="vertical" margin={{ top: 0, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid horizontal={false} stroke={COLORS.grid} />
        <XAxis type="number" tickFormatter={(v) => compactNum(Number(v))} {...axisProps} />
        <YAxis type="category" dataKey="label" width={140} tickFormatter={clip} {...axisProps} />
        <Tooltip {...tooltipProps} formatter={(value) => format(Number(value))} />
        <Bar dataKey="value" name={name} fill={color} radius={[0, 4, 4, 0]} barSize={20} />
      </BarChart>
    </ResponsiveContainer>
  );
}

export interface StackSeries {
  key: string;
  name: string;
  color: string;
}

/** Locations down the side, one stacked segment per ad. */
export function StackedLocationChart({
  data,
  series,
}: {
  data: Array<Record<string, string | number>>;
  series: StackSeries[];
}) {
  const height = Math.max(180, data.length * 42 + 60);
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} layout="vertical" margin={{ top: 0, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid horizontal={false} stroke={COLORS.grid} />
        <XAxis type="number" tickFormatter={(v) => compactNum(Number(v))} {...axisProps} />
        <YAxis type="category" dataKey="location" width={140} tickFormatter={clip} {...axisProps} />
        <Tooltip {...tooltipProps} formatter={(value) => num(Number(value))} />
        <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
        {series.map((s) => (
          <Bar key={s.key} dataKey={s.key} name={s.name} stackId="reach" fill={s.color} barSize={22} />
        ))}
      </BarChart>
    </ResponsiveContainer>
  );
}

/** Two bars per client: leads and sales count. */
export function ClientCompareChart({
  data,
}: {
  data: Array<{ client: string; leads: number; sales: number }>;
}) {
  return (
    <ResponsiveContainer width="100%" height={Math.max(200, data.length * 56 + 60)}>
      <BarChart data={data} layout="vertical" margin={{ top: 0, right: 16, left: 0, bottom: 0 }}>
        <CartesianGrid horizontal={false} stroke={COLORS.grid} />
        <XAxis type="number" allowDecimals={false} {...axisProps} />
        <YAxis type="category" dataKey="client" width={140} tickFormatter={clip} {...axisProps} />
        <Tooltip {...tooltipProps} />
        <Legend iconType="circle" wrapperStyle={{ fontSize: 13 }} />
        <Bar dataKey="leads" name="Leads" fill={COLORS.leads} radius={[0, 4, 4, 0]} barSize={14} />
        <Bar dataKey="sales" name="Sales" fill={COLORS.agent} radius={[0, 4, 4, 0]} barSize={14} />
      </BarChart>
    </ResponsiveContainer>
  );
}

function clip(value: unknown): string {
  const text = String(value);
  return text.length > 22 ? `${text.slice(0, 21)}…` : text;
}
