import { useRef, useState } from "react";
import type { FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  BookOpen,
  Download,
  FileText,
  RefreshCw,
  Upload,
  X,
} from "lucide-react";
import { api, post } from "../../api/client";
import type { Schemas } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import {
  Badge,
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  PageTitle,
  localTime,
} from "../../components/Feedback";

export function KnowledgePage() {
  const { user } = useAuth(),
    admin = user?.role === "admin",
    cache = useQueryClient();
  const [cursor, setCursor] = useState<number | null>(null),
    [error, setError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false),
    [open, setOpen] = useState(false),
    [target, setTarget] = useState<number | null>(null);
  const [file, setFile] = useState<File | null>(null),
    [title, setTitle] = useState(""),
    [uploadError, setUploadError] = useState<unknown>(null);
  const input = useRef<HTMLInputElement>(null);
  const query = useQuery({
    queryKey: ["knowledge", user?.tenant_id, user?.id, cursor],
    queryFn: () =>
      api<Schemas["DocumentPage"]>(
        "/knowledge/documents?limit=20" + (cursor ? "&cursor=" + cursor : ""),
      ),
    refetchInterval: 4000,
  });
  async function toggle(document: Schemas["DocumentDTO"]) {
    setError(null);
    try {
      await api("/knowledge/documents/" + document.id, {
        method: "PATCH",
        body: JSON.stringify({
          enabled: !document.enabled,
          version: document.version,
        }),
      });
    } catch (e) {
      setError(e);
    } finally {
      void cache.invalidateQueries({ queryKey: ["knowledge"] });
    }
  }
  async function retry(job: number) {
    setError(null);
    try {
      await post("/admin/jobs/" + job + "/retry");
      void query.refetch();
    } catch (e) {
      setError(e);
    }
  }
  function start(document?: Schemas["DocumentDTO"]) {
    setTarget(document?.id ?? null);
    setTitle(document?.title ?? "");
    setFile(null);
    setUploadError(null);
    setOpen(true);
  }
  async function submit(event?: FormEvent) {
    event?.preventDefault();
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    const body = new FormData();
    body.append("file", file);
    body.append("title", title || file.name);
    try {
      await api(
        "/knowledge/documents" + (target ? "/" + target + "/versions" : ""),
        { method: "POST", body },
      );
      setOpen(false);
      void query.refetch();
    } catch (e) {
      setUploadError(e);
    } finally {
      setUploading(false);
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="KNOWLEDGE BASE"
        title="知识库"
        description="让每一条回答，都有可追溯的业务依据。"
        action={
          admin ? (
            <button className="button primary" onClick={() => start()}>
              <Upload size={15} />
              上传文档
            </button>
          ) : (
            <span className="pill">只读访问</span>
          )
        }
      />
      <div className="knowledge-intro">
        <span className="assistant-symbol">
          <BookOpen size={21} />
        </span>
        <div>
          <h3>为服务团队维护统一知识</h3>
          <p>
            支持 TXT、Markdown 和带文本的 PDF，单个文件不超过 10
            MiB。新版本就绪后才会替换当前版本。
          </p>
        </div>
        <span className="pill">权限隔离 · 来源可查</span>
      </div>
      {error != null && <ErrorNotice error={error} />}
      <div className="card">
        {query.isLoading ? (
          <Loading />
        ) : query.error ? (
          <ErrorNotice error={query.error} retry={() => void query.refetch()} />
        ) : !query.data?.items.length ? (
          <Empty
            title="还没有知识文档"
            detail="上传售后政策、服务说明或常见问题，为客户问答提供依据。"
            action={
              admin ? (
                <button className="button" onClick={() => start()}>
                  上传第一份文档
                </button>
              ) : undefined
            }
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>文档名称</th>
                  <th>索引状态</th>
                  <th>启用状态</th>
                  <th>最近版本</th>
                  <th>创建时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((document) => (
                  <tr key={document.id}>
                    <td>
                      <div className="row">
                        <span className="file-icon">
                          <FileText size={19} />
                        </span>
                        <div>
                          <strong className="table-title">
                            {document.title}
                          </strong>
                          <span className="table-subtitle">
                            {document.latest_version?.filename} ·{" "}
                            {Math.ceil(
                              (document.latest_version?.size_bytes ?? 0) / 1024,
                            )}{" "}
                            KB
                          </span>
                        </div>
                      </div>
                    </td>
                    <td>
                      <Badge
                        value={document.latest_version?.status ?? "queued"}
                      />
                      {document.latest_version?.error_code && (
                        <span className="table-subtitle">
                          {document.latest_version.error_code}
                        </span>
                      )}
                    </td>
                    <td>
                      <button
                        role="switch"
                        aria-label={
                          (document.enabled ? "启用 " : "停用 ") +
                          document.title
                        }
                        aria-checked={document.enabled}
                        disabled={!admin}
                        className={"switch " + (document.enabled ? "on" : "")}
                        onClick={() => void toggle(document)}
                      >
                        <i />
                      </button>
                      <span className="switch-label">
                        {document.enabled ? "已启用" : "已停用"}
                      </span>
                    </td>
                    <td>
                      V{document.latest_version?.number ?? 1}
                      <span className="table-subtitle">
                        {document.active_version_id ? "有生效版本" : "尚未生效"}
                      </span>
                    </td>
                    <td>{localTime(document.created_at)}</td>
                    <td>
                      <div className="row">
                        <a
                          className="icon-button"
                          href={
                            "/api/v1/knowledge/documents/" +
                            document.id +
                            "/download"
                          }
                          title="下载原文"
                          target="_blank"
                          rel="noreferrer"
                        >
                          <Download size={15} />
                        </a>
                        {admin && (
                          <>
                            <button
                              title="更新版本"
                              className="icon-button"
                              onClick={() => start(document)}
                            >
                              <Upload size={15} />
                            </button>
                            {document.latest_version?.status === "failed" &&
                              document.latest_version.job_id && (
                                <button
                                  title="重试索引"
                                  className="icon-button"
                                  onClick={() =>
                                    void retry(document.latest_version!.job_id!)
                                  }
                                >
                                  <RefreshCw size={15} />
                                </button>
                              )}
                            <button
                              title="删除文档"
                              className="icon-button"
                              onClick={() => {
                                if (
                                  window.confirm(
                                    "确认删除文档？删除后将停止引用。",
                                  )
                                )
                                  void api(
                                    "/knowledge/documents/" + document.id,
                                    { method: "DELETE" },
                                  )
                                    .then(() => query.refetch())
                                    .catch(setError);
                              }}
                            >
                              <X size={15} />
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {(cursor || query.data?.next_cursor) && (
          <div className="pagination">
            {cursor && (
              <button className="button small" onClick={() => setCursor(null)}>
                返回第一页
              </button>
            )}
            {query.data?.next_cursor && (
              <button
                className="button small"
                onClick={() => setCursor(query.data!.next_cursor!)}
              >
                下一页
              </button>
            )}
          </div>
        )}
      </div>
      {open && (
        <Modal
          title={target ? "上传新版本" : "上传知识文档"}
          onClose={() => {
            if (!uploading) setOpen(false);
          }}
        >
          <form className="stack" onSubmit={submit}>
            {uploadError != null && (
              <ErrorNotice error={uploadError} retry={() => void submit()} />
            )}
            <label>
              文档名称
              <input
                required
                maxLength={200}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder="例如：退换货服务政策"
              />
            </label>
            <input
              ref={input}
              type="file"
              accept=".txt,.md,.pdf"
              className="visually-hidden"
              aria-label="选择知识文档"
              onChange={(e) => {
                const selected = e.target.files?.[0] ?? null;
                setFile(selected);
                if (selected && !title) setTitle(selected.name);
              }}
            />
            <button
              type="button"
              className="upload-zone"
              onClick={() => input.current?.click()}
            >
              <Upload size={26} />
              <strong>{file ? file.name : "点击选择文档"}</strong>
              <span>TXT / Markdown / 文本 PDF · 最大 10 MiB</span>
            </button>
            <p className="muted help-text">
              文档将在后台解析和建立索引。含敏感信息的文档请在上传前核对授权范围。
            </p>
            <div className="form-actions">
              <button
                type="button"
                className="button"
                disabled={uploading}
                onClick={() => setOpen(false)}
              >
                取消
              </button>
              <button className="button primary" disabled={!file || uploading}>
                {uploading ? "正在上传…" : "上传并建立索引"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}
