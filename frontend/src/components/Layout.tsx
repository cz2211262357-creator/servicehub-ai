import { NavLink, Outlet } from "react-router-dom";
import {
  BookOpen,
  ChartNoAxesCombined,
  ClipboardList,
  LogOut,
  MessagesSquare,
  Package,
  Settings,
  Users,
} from "lucide-react";
import { useAuth } from "../app/AuthProvider";

export function Brand() {
  return (
    <span className="brand">
      <span className="brand-mark">
        <i />
        <i />
        <i />
        <i />
      </span>
      <span>
        ServiceHub<span className="brand-ai">AI</span>
      </span>
    </span>
  );
}
export function Layout() {
  const auth = useAuth();
  const customer = auth.user?.role === "customer";
  const links = customer
    ? [
        ["/portal", "服务会话", MessagesSquare],
        ["/portal/orders", "我的订单", Package],
        ["/portal/tickets", "售后申请", ClipboardList],
      ]
    : ([
        ["/app", "会话工作台", MessagesSquare],
        ["/app/orders", "订单管理", Package],
        ["/app/tickets", "售后工单", ClipboardList],
        ["/app/knowledge", "知识库", BookOpen],
        ["/app/overview", "运营概览", ChartNoAxesCombined],
        ...(auth.user?.role === "admin"
          ? [["/app/members", "成员与权限", Users] as const]
          : []),
        ["/app/settings", "运行设置", Settings],
      ] as const);
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <Brand />
        <div className="workspace-chip">
          <span className="workspace-logo">S</span>
          <div>
            <strong>{customer ? "客户服务中心" : "客户服务工作空间"}</strong>
            <small>企业 {auth.user?.tenant_id}</small>
          </div>
          <span className="online-dot" />
        </div>
        <p className="nav-caption">{customer ? "客户服务" : "工作空间"}</p>
        <nav>
          {links.map(([path, label, Icon]) => (
            <NavLink
              key={path as string}
              to={path as string}
              end={path === "/app" || path === "/portal"}
            >
              <Icon size={19} />
              <span>{label as string}</span>
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="account">
            <span className="avatar">
              {auth.user?.display_name.slice(0, 1)}
            </span>
            <div>
              <strong>{auth.user?.display_name}</strong>
              <small>
                {
                  { customer: "客户", agent: "客服", admin: "管理员" }[
                    auth.user?.role ?? "customer"
                  ]
                }
              </small>
            </div>
            <button
              className="icon-button"
              title="退出登录"
              onClick={() => void auth.logout()}
            >
              <LogOut size={17} />
            </button>
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <span>
            <span className="online-dot" />
            ServiceHub / {customer ? "客户门户" : "服务控制台"}
          </span>
          <span className="topbar-right">
            让服务有据可循
            <button
              className="icon-button"
              aria-label="退出登录"
              onClick={() => void auth.logout()}
            >
              <LogOut size={17} />
            </button>
            <span className="topbar-avatar">
              {auth.user?.display_name.slice(0, 1)}
            </span>
          </span>
        </header>
        <main className="page-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
