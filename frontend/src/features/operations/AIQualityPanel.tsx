import { Clock3, ShieldCheck, Sparkles, Wallet } from "lucide-react";
import { configuredCost, observed, rate } from "./quality";
import type { AIQualityMetrics } from "./quality";

export function AIQualityPanel({ data }: { data: AIQualityMetrics }) {
  const cost = configuredCost(data);
  const cards = [
    {
      title: "AI 终态失败率",
      value: rate(data.ai_failure_rate),
      Icon: ShieldCheck,
      note: `${observed(data.ai_failed)} / ${observed(data.ai_terminal_samples)} 个成功或失败的运行；取消 ${observed(data.ai_cancelled)} 次另计`,
    },
    {
      title: "AI P95 响应",
      value:
        data.ai_p95_latency_ms == null
          ? "未知"
          : `${data.ai_p95_latency_ms.toLocaleString()} ms`,
      Icon: Clock3,
      note: `${observed(data.ai_latency_samples)} 个有耗时的成功或失败运行样本`,
    },
    {
      title: "模型 / 工具调用",
      value: `${observed(data.model_calls)} / ${observed(data.tool_calls)}`,
      Icon: Sparkles,
      note: `包含失败与重试；检索 ${observed(data.retrieval_calls)} 次`,
    },
    {
      title: "模型估算费用",
      value: cost ?? "费用未知",
      Icon: Wallet,
      note: cost
        ? `价格版本 ${data.price_version} · 最终账单以供应商为准`
        : "需明确价格、币种、价格版本与完整用量",
    },
  ];
  return (
    <section className="ai-quality-section" aria-label="AI 运行质量">
      <div className="card-header">
        <div>
          <h3>AI 运行质量</h3>
          <p>失败率只计成功与最终失败；取消另计。P95 使用有耗时的完成运行。</p>
        </div>
      </div>
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
    </section>
  );
}
