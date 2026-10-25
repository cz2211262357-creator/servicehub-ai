package cn.servicehub.contract;

import cn.servicehub.auth.Role;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoleContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    @Test void roleOutputUsesExistingLowercaseWireValue() {
        assertThat(mapper.writeValueAsString(Role.CUSTOMER)).isEqualTo("\"customer\"");
    }
    @Test void acceptsExistingLowercaseRole() {
        assertThat(mapper.readValue("\"agent\"", Role.class)).isEqualTo(Role.AGENT);
    }
    @Test void rejectsUppercaseRoleOutsideExistingSchema() {
        assertThatThrownBy(() -> mapper.readValue("\"ADMIN\"", Role.class)).isInstanceOf(RuntimeException.class);
    }
}
