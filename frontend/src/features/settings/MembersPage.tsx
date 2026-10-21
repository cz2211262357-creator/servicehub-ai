import { useState } from "react";
import type { FormEvent } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { Plus, Users } from "lucide-react";
import { api, post } from "../../api/client";
import type { Schemas } from "../../api/client";
import { useAuth } from "../../app/AuthProvider";
import {
  Empty,
  ErrorNotice,
  Loading,
  Modal,
  PageTitle,
  localTime,
} from "../../components/Feedback";

const ROLE: Record<string, string> = {
  admin: "管理员",
  agent: "客服",
  customer: "客户",
};
export function MembersPage() {
  const { user } = useAuth(),
    cache = useQueryClient();
  const [cursor, setCursor] = useState<number | null>(null),
    [open, setOpen] = useState(false),
    [editing, setEditing] = useState<Schemas["UserDTO"] | null>(null);
  const [name, setName] = useState(""),
    [email, setEmail] = useState(""),
    [password, setPassword] = useState(""),
    [role, setRole] = useState("agent"),
    [active, setActive] = useState(true),
    [error, setError] = useState<unknown>(null),
    [busy, setBusy] = useState(false);
  const query = useQuery({
    queryKey: ["members", user?.tenant_id, user?.id, cursor],
    queryFn: () =>
      api<Schemas["MemberPage"]>(
        "/admin/members?limit=20" + (cursor ? "&cursor=" + cursor : ""),
      ),
    enabled: user?.role === "admin",
  });
  if (user?.role !== "admin")
    return (
      <Empty
        title="仅管理员可管理成员"
        detail="如需调整权限，请联系企业管理员。"
      />
    );
  function start(member?: Schemas["UserDTO"]) {
    setEditing(member ?? null);
    setName(member?.display_name ?? "");
    setEmail(member?.email ?? "");
    setRole(member?.role ?? "agent");
    setActive(member?.is_active ?? true);
    setPassword("");
    setError(null);
    setOpen(true);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (editing)
        await api("/admin/members/" + editing.id, {
          method: "PATCH",
          body: JSON.stringify({ display_name: name, role, is_active: active }),
        });
      else
        await post("/admin/members", {
          display_name: name,
          email,
          password,
          role,
        });
      setOpen(false);
      setPassword("");
      void cache.invalidateQueries({ queryKey: ["members"] });
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageTitle
        eyebrow="TEAM & ACCESS"
        title="成员与权限"
        description="为团队分配合适的权限，停用账号会撤销现有登录会话。"
        action={
          <button className="button primary" onClick={() => start()}>
            <Plus size={16} />
            添加成员
          </button>
        }
      />
      <div className="card">
        <div className="card-header">
          <div className="row">
            <Users size={17} />
            <h3>企业成员</h3>
          </div>
          <span className="muted help-text">仅显示当前企业账号</span>
        </div>
        {query.isLoading ? (
          <Loading />
        ) : query.error ? (
          <ErrorNotice error={query.error} retry={() => void query.refetch()} />
        ) : !query.data?.items.length ? (
          <Empty title="暂无成员" />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>成员</th>
                  <th>角色</th>
                  <th>账号状态</th>
                  <th>创建时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((member) => (
                  <tr key={member.id}>
                    <td>
                      <div className="row">
                        <span className="avatar">
                          {member.display_name.slice(0, 1)}
                        </span>
                        <div>
                          <strong className="table-title">
                            {member.display_name}
                            {member.id === user?.id ? "（我）" : ""}
                          </strong>
                          <span className="table-subtitle">{member.email}</span>
                        </div>
                      </div>
                    </td>
                    <td>
                      <span className={"role-label role-" + member.role}>
                        {ROLE[member.role]}
                      </span>
                    </td>
                    <td>
                      <span
                        className={
                          "badge " +
                          (member.is_active
                            ? "status-succeeded"
                            : "status-closed")
                        }
                      >
                        <i />
                        {member.is_active ? "正常" : "已停用"}
                      </span>
                    </td>
                    <td>{localTime(member.created_at)}</td>
                    <td>
                      <button
                        className="button subtle small"
                        onClick={() => start(member)}
                      >
                        编辑成员
                      </button>
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
          title={editing ? "编辑成员" : "添加成员"}
          onClose={() => setOpen(false)}
        >
          <form onSubmit={submit} className="stack">
            {error != null && <ErrorNotice error={error} />}
            <label>
              显示名称
              <input
                required
                maxLength={80}
                value={name}
                onChange={(e) => setName(e.target.value)}
              />
            </label>
            {!editing && (
              <>
                <label>
                  邮箱
                  <input
                    required
                    type="email"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                  />
                </label>
                <label>
                  初始密码
                  <input
                    required
                    type="password"
                    minLength={12}
                    maxLength={128}
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    autoComplete="new-password"
                  />
                </label>
                <p className="muted help-text">
                  密码至少 12 位，请通过合适的内部方式告知成员。
                </p>
              </>
            )}
            <label>
              角色
              <select
                value={role}
                disabled={editing?.id === user?.id}
                onChange={(e) => setRole(e.target.value)}
              >
                {Object.entries(ROLE).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            {editing && (
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={active}
                  disabled={editing.id === user?.id}
                  onChange={(e) => setActive(e.target.checked)}
                />
                允许账号登录
              </label>
            )}
            <div className="form-actions">
              <button
                type="button"
                className="button"
                onClick={() => setOpen(false)}
              >
                取消
              </button>
              <button className="button primary" disabled={busy}>
                {busy ? "正在保存…" : "保存成员"}
              </button>
            </div>
          </form>
        </Modal>
      )}
    </>
  );
}
