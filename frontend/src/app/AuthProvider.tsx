import { createContext, useContext, useEffect, useState } from "react";
import type { ReactNode } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, post, setCsrf } from "../api/client";
import type { Schemas } from "../api/client";

type User = Schemas["UserDTO"];
type Auth = {
  user: User | null;
  loading: boolean;
  login: (tenant: number, email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};
const Context = createContext<Auth | null>(null);
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const cache = useQueryClient();
  useEffect(() => {
    let alive = true;
    api<Schemas["SessionDTO"]>("/auth/me")
      .then((result) => {
        if (alive) {
          setUser(result.user);
          setCsrf(result.csrf_token);
        }
      })
      .catch(() => {})
      .finally(() => {
        if (alive) setLoading(false);
      });
    const expire = () => {
      setUser(null);
      setCsrf("");
      cache.clear();
    };
    window.addEventListener("session-expired", expire);
    return () => {
      alive = false;
      window.removeEventListener("session-expired", expire);
    };
  }, [cache]);
  async function login(tenant: number, email: string, password: string) {
    const result = await post<Schemas["SessionDTO"]>("/auth/login", {
      tenant_id: tenant,
      email,
      password,
    });
    cache.clear();
    setCsrf(result.csrf_token);
    setUser(result.user);
  }
  async function logout() {
    await post("/auth/logout");
    setUser(null);
    setCsrf("");
    cache.clear();
  }
  return (
    <Context.Provider value={{ user, loading, login, logout }}>
      {children}
    </Context.Provider>
  );
}
export function useAuth() {
  const value = useContext(Context);
  if (!value) throw new Error("Auth provider missing");
  return value;
}
