import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ArrowUpRight, Package, Search } from "lucide-react";
import { api } from "../../api/client";
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
  money,
} from "../../components/Feedback";
import { AfterSaleForm } from "./AfterSaleForm";

export function OrderPanel() {
  const user = useAuth().user;
  const customer = user?.role === "customer";
  const [cursor, setCursor] = useState<number | null>(null),
    [filter, setFilter] = useState(""),
    [search, setSearch] = useState(""),
    [number, setNumber] = useState<string | null>(null),
    [apply, setApply] = useState(false);
  const query = useQuery({
    queryKey: ["orders", user?.tenant_id, user?.id, cursor, search],
    queryFn: () =>
      api<Schemas["OrderPage"]>(
        "/orders?limit=20" +
          (cursor ? "&cursor=" + cursor : "") +
          (search ? "&search=" + encodeURIComponent(search) : ""),
      ),
  });
  const detail = useQuery({
    queryKey: ["order", user?.tenant_id, user?.id, number],
    queryFn: () =>
      api<Schemas["OrderDTO"]>("/orders/" + encodeURIComponent(number!)),
    enabled: !!number,
  });
  return (
    <>
      <PageTitle
        eyebrow="ORDERS"
        title={customer ? "我的订单" : "订单管理"}
        description={
          customer
            ? "查看订单与物流信息，按需发起售后申请。"
            : "按订单号查找本企业订单，查看商品及物流信息。"
        }
      />
      <form
        className="toolbar"
        onSubmit={(e) => {
          e.preventDefault();
          setCursor(null);
          setSearch(filter.trim());
        }}
      >
        <input
          aria-label="订单号"
          placeholder="输入完整订单号"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <button className="button">
          <Search size={15} />
          查询
        </button>
        {search && (
          <button
            type="button"
            className="button subtle"
            onClick={() => {
              setSearch("");
              setFilter("");
            }}
          >
            清除
          </button>
        )}
      </form>
      <div className="card">
        {query.isLoading ? (
          <Loading />
        ) : query.error ? (
          <ErrorNotice error={query.error} retry={() => void query.refetch()} />
        ) : !query.data?.items.length ? (
          <Empty
            title="暂无订单"
            detail="查询其他订单号，或联系服务团队确认。"
          />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>订单编号</th>
                  <th>订单状态</th>
                  <th>订单金额</th>
                  <th>物流</th>
                  <th>下单时间</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {query.data.items.map((order) => (
                  <tr key={order.id}>
                    <td>
                      <strong className="table-title">
                        {order.order_number}
                      </strong>
                      <span className="table-subtitle">
                        客户 #{order.customer_id}
                      </span>
                    </td>
                    <td>
                      <Badge value={order.status} />
                    </td>
                    <td>{money(order.total_amount)}</td>
                    <td>
                      {order.carrier || "暂无物流"}
                      <span className="table-subtitle">
                        {order.tracking_number ?? "—"}
                      </span>
                    </td>
                    <td>{localTime(order.created_at)}</td>
                    <td>
                      <button
                        className="button subtle small"
                        onClick={() => {
                          setNumber(order.order_number);
                          setApply(false);
                        }}
                      >
                        查看详情
                        <ArrowUpRight size={13} />
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
      {number && (
        <Modal
          title={apply ? "准备售后申请" : "订单详情"}
          onClose={() => setNumber(null)}
        >
          {detail.isLoading ? (
            <Loading />
          ) : detail.error ? (
            <ErrorNotice error={detail.error} />
          ) : (
            detail.data &&
            (apply ? (
              <AfterSaleForm
                orderId={detail.data.id}
                onDone={() => setNumber(null)}
              />
            ) : (
              <>
                <div className="order-detail-title">
                  <Package size={25} />
                  <div>
                    <h3>{detail.data.order_number}</h3>
                    <small className="muted">
                      {localTime(detail.data.created_at)}
                    </small>
                  </div>
                  <Badge value={detail.data.status} />
                </div>
                <div className="stack">
                  {(detail.data.items ?? []).map((item) => (
                    <div className="order-product" key={item.id}>
                      <div>
                        <strong>{item.product_name}</strong>
                        <small>
                          {item.sku} · 数量 {item.quantity}
                        </small>
                      </div>
                      <span>{money(item.unit_price)}</span>
                    </div>
                  ))}
                </div>
                <hr className="divider" />
                <dl className="detail-grid">
                  <div>
                    <dt>订单金额</dt>
                    <dd>{money(detail.data.total_amount)}</dd>
                  </div>
                  <div>
                    <dt>物流单号</dt>
                    <dd>{detail.data.tracking_number || "暂无物流"}</dd>
                  </div>
                  <div>
                    <dt>配送进度</dt>
                    <dd>{detail.data.delivery_summary || "暂无配送进度"}</dd>
                  </div>
                </dl>
                {customer &&
                  ["DELIVERED", "SHIPPED"].includes(detail.data.status) && (
                    <div className="form-actions">
                      <button
                        className="button primary"
                        onClick={() => setApply(true)}
                      >
                        申请售后
                      </button>
                    </div>
                  )}
              </>
            ))
          )}
        </Modal>
      )}
    </>
  );
}
