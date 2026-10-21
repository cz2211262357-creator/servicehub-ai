import { useState } from "react";
import type { FormEvent } from "react";
import { Navigate, Outlet, Route, Routes } from "react-router-dom";
import { ArrowRight, BookOpen, Headphones, ShieldCheck } from "lucide-react";
import { useAuth } from "./AuthProvider";
import { PortalPage } from "../features/portal/PortalPage";
import { OrderPanel } from "../features/portal/OrderPanel";
import { TicketPage } from "../features/portal/TicketPage";
import { InboxPage } from "../features/inbox/InboxPage";
import { KnowledgePage } from "../features/knowledge/KnowledgePage";
import { MembersPage } from "../features/settings/MembersPage";
import { OverviewPage } from "../features/operations/OverviewPage";
import { JobsPage } from "../features/operations/JobsPage";
import { AuditPage } from "../features/operations/AuditPage";
import { SettingsPage } from "../features/settings/SettingsPage";
import { Brand, Layout } from "../components/Layout";
import { Empty, ErrorNotice, Loading } from "../components/Feedback";

function Protected({
  customer = false,
  admin = false,
}: {
  customer?: boolean;
  admin?: boolean;
}) {
  const { user, loading } = useAuth();
  if (loading) return <Loading />;
  if (!user) return <Navigate to="/login" replace />;
  if (customer && user.role !== "customer")
    return <Navigate to="/app" replace />;
  if (!customer && user.role === "customer")
    return <Navigate to="/portal" replace />;
  if (admin && user.role !== "admin") return <Empty title="没有访问权限" />;
  return <Outlet />;
}
function Login() {
  const auth = useAuth();
  const [tenant, setTenant] = useState("1"),
    [email, setEmail] = useState(""),
    [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState<unknown>(null);
  if (auth.loading) return <Loading />;
  if (auth.user)
    return (
      <Navigate
        to={auth.user.role === "customer" ? "/portal" : "/app"}
        replace
      />
    );
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await auth.login(Number(tenant), email, password);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="login-page">
      <section className="login-story">
        <Brand />
        <div className="story-content">
          <div className="eyebrow">CONNECTED CUSTOMER SERVICE</div>
          <h1>
            每一次咨询，
            <br />
            都有下一步。
          </h1>
          <p>
            连接知识、客户与服务团队，
            <br />
            让咨询与售后处理在同一处完成。
          </p>
          <div className="story-features">
            <span>
              <BookOpen />
              有据可循的回答
            </span>
            <span>
              <Headphones />
              顺畅的人工协作
            </span>
            <span>
              <ShieldCheck />
              清晰的服务记录
            </span>
          </div>
        </div>
        <footer>ServiceHub AI · 客户服务平台</footer>
      </section>
      <section className="login-form-area">
        <form className="login-form" onSubmit={submit}>
          <span className="eyebrow">WELCOME BACK</span>
          <h2>登录工作空间</h2>
          <p>使用所属企业的账号继续</p>
          {error != null && <ErrorNotice error={error} />}
          <label>
            企业编号
            <input
              required
              type="number"
              min="1"
              value={tenant}
              onChange={(e) => setTenant(e.target.value)}
              autoComplete="organization"
            />
          </label>
          <label>
            邮箱
            <input
              type="email"
              required
              placeholder="输入账号邮箱"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
            />
          </label>
          <label>
            密码
            <input
              type="password"
              required
              placeholder="输入登录密码"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>
          <button className="button primary wide" disabled={busy}>
            {busy ? "正在登录…" : "登录"}
            <ArrowRight size={18} />
          </button>
          <small className="login-note">
            账号由企业管理员分配。若无法登录，请联系管理员。
          </small>
        </form>
      </section>
    </div>
  );
}
export function Router() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<Protected customer />}>
        <Route path="/portal" element={<Layout />}>
          <Route index element={<PortalPage />} />
          <Route path="orders" element={<OrderPanel />} />
          <Route path="tickets" element={<TicketPage />} />
        </Route>
      </Route>
      <Route element={<Protected />}>
        <Route path="/app" element={<Layout />}>
          <Route index element={<InboxPage />} />
          <Route path="orders" element={<OrderPanel />} />
          <Route path="tickets" element={<TicketPage />} />
          <Route path="knowledge" element={<KnowledgePage />} />
          <Route path="members" element={<MembersPage />} />
          <Route path="overview" element={<OverviewPage />} />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="jobs" element={<JobsPage />} />
          <Route path="audits" element={<AuditPage />} />
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
