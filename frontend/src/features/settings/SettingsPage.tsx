import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  CheckCircle2,
  Cpu,
  Database,
  ShieldCheck,
} from "lucide-react";
import { api } from "../../api/client";
import type { Schemas } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import { ErrorNotice, Loading, PageTitle } from "../../components/Feedback";
export function SettingsPage() {
  const { user } = useAuth();
  const query = useQuery({
    queryKey: ["runtime", user?.tenant_id, user?.id],
    queryFn: () => api<Schemas["RuntimeStatus"]>("/admin/runtime"),
  });
  const ready = useQuery({
    queryKey: ["readiness", user?.tenant_id, user?.id],
    queryFn: () => api("/health/ready"),
    refetchInterval: 15000,
    retry: false,
  });
  return (
    <>
      <PageTitle
        eyebrow="SERVICE STATUS"
        title="运行设置"
        description="查看服务依赖与模型接入配置。密钥仅在服务器环境中管理。"
      />
      {query.isLoading ? (
        <Loading />
      ) : query.error ? (
        <ErrorNotice error={query.error} />
      ) : (
        query.data && (
          <div className="settings-grid">
            <section className="card">
              <div className="card-header">
                <div className="row">
                  <Cpu size={17} />
                  <h3>在线模型服务</h3>
                </div>
                <span className="pill">
                  {query.data.model_mode === "online" ? "在线模式" : "测试模式"}
                </span>
              </div>
              <div className="settings-list">
                <div>
                  <span>对话模型</span>
                  <strong>{query.data.model_name || "未配置"}</strong>
                </div>
                <div>
                  <span>接入状态</span>
                  <strong>
                    {query.data.model_configured ? "已配置" : "待配置"}
                  </strong>
                </div>
                <div>
                  <span>Embedding 模型</span>
                  <strong>{query.data.embedding_model || "未配置"}</strong>
                </div>
                <div>
                  <span>向量维度</span>
                  <strong>{query.data.embedding_dimensions}</strong>
                </div>
                <div>
                  <span>向量服务配置</span>
                  <strong>
                    {query.data.embedding_configured ? "已配置" : "待配置"}
                  </strong>
                </div>
              </div>
              <p className="settings-note">
                配置状态表示必要参数已填写，实际可用性以模型调用结果为准。
              </p>
            </section>
            <section className="card">
              <div className="card-header">
                <div className="row">
                  <ShieldCheck size={17} />
                  <h3>容量与处理边界</h3>
                </div>
              </div>
              <div className="settings-list">
                <div>
                  <span>模型同时执行上限</span>
                  <strong>{query.data.global_model_slots}</strong>
                </div>
                <div>
                  <span>单次回答输出预算</span>
                  <strong>{query.data.max_output_tokens} Token</strong>
                </div>
                <div>
                  <span>文档大小上限</span>
                  <strong>
                    {query.data.max_upload_bytes / 1024 / 1024} MiB
                  </strong>
                </div>
                <div>
                  <span>自动重试上限</span>
                  <strong>{query.data.automatic_retries} 次</strong>
                </div>
              </div>
              <p className="settings-note">
                售后申请需要客户确认；不执行真实支付或资金退款。
              </p>
            </section>
            <section className="card">
              <div className="card-header">
                <div className="row">
                  <Database size={17} />
                  <h3>服务依赖</h3>
                </div>
              </div>
              <div className="settings-list">
                <div>
                  <span>整体就绪状态</span>
                  <strong className="row">
                    {ready.error ? (
                      <>
                        <Activity size={14} />
                        暂不可用
                      </>
                    ) : (
                      <>
                        <CheckCircle2 size={14} />
                        {ready.isLoading ? "检查中" : "就绪"}
                      </>
                    )}
                  </strong>
                </div>
                {Object.entries(query.data.dependencies ?? {}).map(
                  ([name, status]) => (
                    <div key={name}>
                      <span>
                        {{
                          database: "MySQL",
                          redis: "Redis",
                          rabbitmq: "RabbitMQ",
                        }[name] ?? name}
                      </span>
                      <strong>{status === "ok" ? "就绪" : "暂不可用"}</strong>
                    </div>
                  ),
                )}
              </div>
              {ready.error && (
                <ErrorNotice
                  error={ready.error}
                  retry={() => void ready.refetch()}
                />
              )}
              <p className="settings-note">
                依赖不可用时停止需要可靠限流和调度的业务写入。
              </p>
            </section>
          </div>
        )
      )}
    </>
  );
}
