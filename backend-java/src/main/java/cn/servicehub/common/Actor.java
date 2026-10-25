package cn.servicehub.common;

import cn.servicehub.auth.Role;

public record Actor(long tenantId, long userId, Role role, long authVersion) { }
