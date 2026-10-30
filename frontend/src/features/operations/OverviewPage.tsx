import { useState } from "react";
import "./operations.css";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowUpRight,
  Clock3,
  Headphones,
  MessagesSquare,
  RefreshCw,
  ShieldCheck,
  Sparkles,
} from "lucide-react";
import { api } from "../../api/client";
import { AIQualityPanel } from "./AIQualityPanel";
import { RunTracePanel } from "./RunTracePanel";
import { observed, rate } from "./quality";
import type { CompatibleMetrics } from "./quality";
import { useAuth } from "../../app/AuthProvider";
import {
  Empty,
  ErrorNotice,
  Loading,
  PageTitle,
} from "../../components/Feedback";

export function OverviewPage() {
  const { user } = useAuth(),
    [days, setDays] = useState("7");
  const query = useQuery({
    queryKey: ["metrics", user?.tenant_id, user?.id, days],
    queryFn: () => {
      const end = new Date(),
        start = new Date(end.getTime() - Number(days) * 86400000);
      return api<CompatibleMetrics>(
        "/admin/metrics/summary?start=" +
          encodeURIComponent(start.toISOString()) +
          "&end=" +
          encodeURIComponent(end.toISOString()),
      );
    },
    refetchInterval: 30000,
  });
  const data = query.data;
  const cards = data
    ? [
        {
          title: "会话总量",
          value: String(data.conversations),
          note: "当前时间窗口内创建",
          Icon: MessagesSquare,
        },
        {
          title: "等待人工",
          value: String(data.waiting),
          note: "窗口内尚未接入的会话",
          Icon: Clock3,
        },
        {
          title: "转人工比例",
          value:
            data.handoff_rate == null
              ? "—"
              : (data.handoff_rate * 100).toFixed(1) + "%",
          note: data.handoff_count + " 个会话发起转人工",
          Icon: Headphones,
        },
        {
          title: "首次响应",
          value:
            data.first_response_ms == null
              ? "—"
              : (data.first_response_ms / 1000).toFixed(1) + "s",
          note: "从客户首条消息到首次回复",
          Icon: Sparkles,
        },
      ]
    : [];
  const points = data?.daily ?? [],
    peak = Math.max(1, ...points.map((point) => point.count));
  const path = points
    .map(
      (point, i) =>
        (i ? "L" : "M") +
        (30 + (i * 640) / Math.max(1, points.length - 1)) +
        "," +
        (155 - (point.count / peak) * 115),
    )
    .join(" ");
  return (
    <>
      <PageTitle
        eyebrow="SERVICE ANALYTICS"
        title="运营概览"
        description="从真实服务记录中，了解咨询处理与运行情况。"
        action={
          <div className="row">
            <select
              className="compact-select"
              aria-label="统计范围"
              value={days}
              onChange={(e) => setDays(e.target.value)}
            >
              <option value="7">最近 7 天</option>
              <option value="14">最近 14 天</option>
              <option value="30">最近 30 天</option>
            </select>
            <button className="button" onClick={() => void query.refetch()}>
              <RefreshCw size={14} />
              刷新
            </button>
          </div>
        }
      />
      {query.isLoading ? (
        <Loading />
      ) : query.error ? (
        <ErrorNotice error={query.error} retry={() => void query.refetch()} />
      ) : (
        data && (
          <>
            <div className="stats-grid">
              {cards.map(({ title, value, note, Icon }) => (
                <div className="stat-card" key={title}>
                  <div className="row spread">
                    <span>{title}</span>
                    <Icon size={16} />
                  </div>
                  <strong>{value}</strong>
                  <small>{note}</small>
                </div>
              ))}
            </div>
            <AIQualityPanel data={data} />
            <div className="overview-panels">
              <section className="card">
                <div className="card-header">
                  <div>
                    <h3>咨询趋势</h3>
                    <p>按 UTC 日期汇总真实会话数量</p>
                  </div>
                  <span className="chart-legend">
                    <i />
                    服务会话
                  </span>
                </div>
                {points.length ? (
                  <div className="trend-chart">
                    <svg
                      viewBox="0 0 700 195"
                      role="img"
                      aria-label="每日会话数量折线图"
                    >
                      <defs>
                        <linearGradient
                          id="chart-fill"
                          x1="0"
                          x2="0"
                          y1="0"
                          y2="1"
                        >
                          <stop
                            offset="0%"
                            stopColor="#91b491"
                            stopOpacity=".2"
                          />
                          <stop
                            offset="100%"
                            stopColor="#91b491"
                            stopOpacity="0"
                          />
                        </linearGradient>
                      </defs>
                      {[40, 97, 155].map((y) => (
                        <line
                          key={y}
                          x1="30"
                          x2="670"
                          y1={y}
                          y2={y}
                          stroke="#edf1e7"
                          strokeDasharray="4 5"
                        />
                      ))}
                      <path
                        d={path + " L670,155 L30,155 Z"}
                        fill="url(#chart-fill)"
                      />
                      <path
                        d={path}
                        fill="none"
                        stroke="#7fa476"
                        strokeWidth="2.5"
                      />
                      {points.map((point, i) => (
                        <g key={point.date}>
                          <circle
                            cx={30 + (i * 640) / Math.max(1, points.length - 1)}
                            cy={155 - (point.count / peak) * 115}
                            r="3.5"
                            fill="#fff"
                            stroke="#7fa476"
                            strokeWidth="2"
                          />
                          <text
                            x={30 + (i * 640) / Math.max(1, points.length - 1)}
                            y="183"
                            textAnchor="middle"
                            fontSize="9"
                            fill="#a6b39a"
                          >
                            {point.date.slice(5)}
                          </text>
                          <title>
                            {point.date}：{point.count} 个会话
                          </title>
                        </g>
                      ))}
                    </svg>
                  </div>
                ) : (
                  <Empty
                    title="当前窗口暂无会话"
                    detail="产生服务会话后，这里会展示实际趋势。"
                  />
                )}
              </section>
              <section className="card">
                <div className="card-header">
                  <div>
                    <h3>模型用量</h3>
                    <p>只展示供应商返回的用量记录</p>
                  </div>
                  <Sparkles size={17} />
                </div>
                <div className="usage-panel">
                  {data.total_tokens == null ? (
                    <>
                      <span className="usage-number">—</span>
                      <h3>
                        {data.prompt_tokens != null ||
                        data.completion_tokens != null
                          ? "总 Token 未知，已收到部分用量"
                          : "暂无模型用量记录"}
                      </h3>
                      <p>
                        完成真实模型调用后显示已记录 Token。缺少 usage
                        的调用不会估算补齐。
                      </p>
                    </>
                  ) : (
                    <>
                      <span className="usage-number">
                        {data.total_tokens.toLocaleString()}
                      </span>
                      <h3>已记录 Token</h3>
                      <p>
                        {data.model_calls == null
                          ? `${data.usage_records} / ${data.ai_runs} 次运行返回用量记录`
                          : `${data.usage_records} / ${data.model_calls} 次模型调用返回总 Token（已知下限）`}
                      </p>
                    </>
                  )}
                  {data.model_calls == null ? (
                    data.ai_runs > data.usage_records && (
                      <p>
                        {data.ai_runs - data.usage_records} 次运行未记录用量
                      </p>
                    )
                  ) : (
                    <>
                      <p>总 Token 用量覆盖率 {rate(data.usage_coverage)}</p>
                      <p>
                        {observed(data.usage_unknown_records)}{" "}
                        次模型调用未返回总 Token
                      </p>
                      <p>
                        输入 / 输出已知 Token：{observed(data.prompt_tokens)} /{" "}
                        {observed(data.completion_tokens)}
                      </p>
                    </>
                  )}
                  <div className="usage-footnote">
                    {data.model_calls == null
                      ? "仅显示供应商返回的用量，缺失值保留未知。"
                      : "包含失败与重试的已知消耗；未返回的用量保留未知，不补零。"}
                  </div>
                </div>
              </section>
            </div>
            <section className="operations-strip">
              <div className="row">
                <ShieldCheck size={20} />
                <div>
                  <h3>后台任务与操作记录</h3>
                  <p>
                    {data.failed_jobs
                      ? data.failed_jobs +
                        " 个任务最终失败，可在任务中心查看并重试。"
                      : "当前窗口内没有最终失败的任务。"}
                  </p>
                </div>
              </div>
              {user?.role === "admin" && (
                <div className="row">
                  <Link className="button" to="/app/jobs">
                    任务中心
                    <ArrowUpRight size={13} />
                  </Link>
                  <Link className="button subtle" to="/app/audits">
                    操作审计
                    <ArrowUpRight size={13} />
                  </Link>
                </div>
              )}
            </section>
            <RunTracePanel />
            {data.end && (
              <p className="data-timestamp">
                统计窗口（UTC）：{data.start ?? "未知"} 至 {data.end}。
                {data.model_calls == null
                  ? "当前服务按会话与运行创建时间筛选，尚未提供调用明细指标。"
                  : "会话与 AI 总量按创建时间；AI 终态与 P95 按完成时间；调用与 Token 按调用开始时间。"}
              </p>
            )}
          </>
        )
      )}
    </>
  );
}
