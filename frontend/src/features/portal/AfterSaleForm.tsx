import { useRef, useState } from "react";
import type { FormEvent } from "react";
import { CheckCircle2, ShieldCheck } from "lucide-react";
import { post } from "../../api/client";
import type { Schemas } from "../../api/client";
import { ErrorNotice } from "../../components/Feedback";

type Draft = Schemas["TicketDraftDTO"];
const REASONS = [
  { value: "NO_REASON", label: "七天无理由" },
  { value: "QUALITY", label: "商品质量问题" },
  { value: "WRONG_ITEM", label: "商家发错商品" },
  { value: "LOGISTICS_DAMAGE", label: "物流运输损坏" },
  { value: "OTHER", label: "其他原因，需要进一步核实" },
] as const;
type ReasonCode = (typeof REASONS)[number]["value"];
export function AfterSaleForm({
  orderId,
  onDone,
  initialDraft,
}: {
  orderId: number;
  onDone: () => void;
  initialDraft?: Draft;
}) {
  const [kind, setKind] = useState<Schemas["DraftInput"]["kind"]>("RETURN"),
    [reasonCode, setReasonCode] = useState<ReasonCode | "">(""),
    [reason, setReason] = useState(""),
    [draft, setDraft] = useState<Draft | null>(initialDraft ?? null);
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null),
    [ticket, setTicket] = useState<number | null>(null);
  const key = useRef(crypto.randomUUID());
  async function prepare(event: FormEvent) {
    event.preventDefault();
    if (!reasonCode) return;
    setBusy(true);
    setError(null);
    try {
      setDraft(
        await post<Draft>("/after-sales/drafts", {
          order_id: orderId,
          kind,
          reason,
          reason_code: reasonCode,
        }),
      );
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  async function confirm() {
    if (!draft) return;
    setBusy(true);
    setError(null);
    try {
      const result = await post<Schemas["TicketDTO"]>(
        "/after-sales/drafts/" + draft.id + "/confirm",
        { version: draft.version, idempotency_key: key.current },
      );
      setTicket(result.id);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  if (ticket)
    return (
      <div className="success-panel">
        <CheckCircle2 size={35} />
        <h3>申请已提交</h3>
        <p>工单 #{ticket} 已创建，可在售后申请中查看进度。</p>
        <button className="button primary" onClick={onDone}>
          完成
        </button>
      </div>
    );
  return (
    <div className="stack">
      {error != null && <ErrorNotice error={error} />}
      {draft ? (
        <>
          <div className="notice">
            <ShieldCheck size={18} />
            <span>请核对申请信息。点击确认后才会创建正式工单。</span>
          </div>
          <dl className="detail-grid">
            <div>
              <dt>订单编号</dt>
              <dd>#{orderId}</dd>
            </div>
            <div>
              <dt>申请类型</dt>
              <dd>
                {
                  (
                    {
                      RETURN: "退货申请",
                      EXCHANGE: "换货申请",
                      REPAIR: "维修申请",
                    } as Record<string, string>
                  )[draft.kind]
                }
              </dd>
            </div>
          </dl>
          <div className="review-reason">{draft.reason}</div>
          <div className="form-actions">
            <button
              className="button primary"
              disabled={busy}
              onClick={confirm}
            >
              {busy ? "正在提交…" : "确认提交申请"}
            </button>
          </div>
        </>
      ) : (
        <form onSubmit={prepare} className="stack">
          <label>
            申请类型
            <select
              value={kind}
              onChange={(e) =>
                setKind(e.target.value as Schemas["DraftInput"]["kind"])
              }
            >
              <option value="RETURN">退货申请</option>
              <option value="EXCHANGE">换货申请</option>
              <option value="REPAIR">维修申请</option>
            </select>
          </label>
          <label>
            申请原因
            <select
              required
              value={reasonCode}
              onChange={(e) => setReasonCode(e.target.value as ReasonCode | "")}
            >
              <option value="" disabled>
                请选择申请原因
              </option>
              {REASONS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </label>
          <label>
            申请说明
            <textarea
              required
              minLength={2}
              maxLength={1000}
              placeholder="请描述商品问题及您的诉求"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <p className="muted help-text">
            此操作仅准备申请信息，下一步核对后再确认提交。
          </p>
          <div className="form-actions">
            <button
              className="button primary"
              disabled={busy || !reasonCode || reason.trim().length < 2}
            >
              {busy ? "正在准备…" : "准备申请"}
            </button>
          </div>
        </form>
      )}
    </div>
  );
}
