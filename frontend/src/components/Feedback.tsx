import type { ReactNode } from "react";
import { AlertCircle, Inbox, LoaderCircle, X } from "lucide-react";
import { ApiError } from "../api/client";

export function Loading() {
  return (
    <div className="state-box" role="status">
      <LoaderCircle className="spin" size={24} />
      <span>正在加载…</span>
    </div>
  );
}
export function Empty({
  title = "暂无记录",
  detail,
  action,
}: {
  title?: string;
  detail?: string;
  action?: ReactNode;
}) {
  return (
    <div className="state-box empty">
      <span className="empty-icon">
        <Inbox size={27} />
      </span>
      <h3>{title}</h3>
      {detail && <p>{detail}</p>}
      {action}
    </div>
  );
}
export function ErrorNotice({
  error,
  retry,
}: {
  error: unknown;
  retry?: () => void;
}) {
  const message =
    error instanceof Error ? error.message : "操作未完成，请稍后重试";
  return (
    <div className="error-notice" role="alert">
      <AlertCircle size={18} />
      <div>
        <strong>{message}</strong>
        {error instanceof ApiError && error.requestId && (
          <small>请求编号 {error.requestId}</small>
        )}
        {error instanceof ApiError && error.retryAfter > 0 && (
          <small>请在约 {error.retryAfter} 秒后重试</small>
        )}
      </div>
      {retry && (
        <button className="button subtle small" onClick={retry}>
          重试
        </button>
      )}
    </div>
  );
}
export function Badge({ value }: { value: string }) {
  const labels: Record<string, string> = {
    AI_ACTIVE: "智能接待",
    WAITING_HUMAN: "等待接入",
    HUMAN_ACTIVE: "人工接待",
    CLOSED: "已结束",
    SUBMITTED: "待受理",
    IN_REVIEW: "审核中",
    APPROVED: "已通过",
    REJECTED: "已拒绝",
    RESOLVED: "已完成",
    CANCELLED: "已取消",
    DELIVERED: "已签收",
    SHIPPED: "已发货",
    PAID: "待发货",
    ready: "已就绪",
    queued: "待处理",
    processing: "处理中",
    running: "执行中",
    retrying: "重试中",
    failed: "失败",
    succeeded: "成功",
    cancelled: "已取消",
  };
  return (
    <span className={"badge status-" + value.toLowerCase()}>
      <i />
      {labels[value] ?? value}
    </span>
  );
}
export function PageTitle({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow?: string;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div className="page-title">
      <div>
        {eyebrow && <div className="eyebrow">{eyebrow}</div>}
        <h1>{title}</h1>
        {description && <p>{description}</p>}
      </div>
      {action}
    </div>
  );
}
export function Modal({
  title,
  children,
  onClose,
}: {
  title: string;
  children: ReactNode;
  onClose: () => void;
}) {
  return (
    <div className="modal-shade" onMouseDown={onClose}>
      <section
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <header>
          <h2>{title}</h2>
          <button className="icon-button" aria-label="关闭" onClick={onClose}>
            <X size={20} />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}
export function localTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}
export function money(value: string | number) {
  return new Intl.NumberFormat("zh-CN", {
    style: "currency",
    currency: "CNY",
  }).format(Number(value));
}
