package cn.servicehub.system;
import cn.servicehub.support.SystemScenarioAccess;
import org.junit.jupiter.api.*;
import static cn.servicehub.support.SystemScenarioAccess.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailureRecoverySystemIT {
 final SystemScenarioAccess fixture=new SystemScenarioAccess();
 @AfterAll void close(){fixture.close();}
 @Test void cancelledRunStaysCancelledAfterWorkerRestart(){
  var r=fixture.run("cancel-restart");processes(r);assertThat(number(r,"positive_control_drafts")).isEqualTo(1);assertThat(number(r,"worker_pid_before")).isNotEqualTo(number(r,"worker_pid_after"));
  assertThat(number(r,"old_tools_after_restart")).isZero();assertThat(number(r,"old_answers")).isZero();assertThat(number(r,"current_answers")).isEqualTo(1);assertThat(number(r,"persisted_cancel_observed")).isEqualTo(1);
 }
 @RepeatedTest(3) void expiredWorkerCannotOverwriteReplacementEvenWhenItResumes(){
  var r=fixture.run("lease-fencing");processes(r);assertThat(number(r,"replacement_token")).isGreaterThan(number(r,"old_token"));assertThat(number(r,"old_worker_results")).isZero();assertThat(number(r,"replacement_results")).isEqualTo(1);assertThat(number(r,"lease_gate_hits")).isPositive();assertThat(number(r,"retired_broker_consumers")).isPositive();
 }
 @Test void confirmLossAndBrokerRecoveryPreserveOneLogicalJobAndResult(){
  var r=fixture.run("broker-confirm-loss");processes(r);assertThat(number(r,"confirm_loss_hits")).isPositive();assertThat(number(r,"logical_jobs")).isEqualTo(1);assertThat(number(r,"final_results")).isEqualTo(1);assertThat(number(r,"premature_dispatched")).isZero();
 }
 @Test void databaseAndProviderFailuresRespectDurableAckAndThreeAttemptBudget(){
  var r=fixture.run("dependency-retry");processes(r);assertThat(number(r,"db_fault_hits")).isPositive();assertThat(number(r,"acks_without_disposition")).isZero();assertThat(number(r,"maximum_attempts")).isLessThanOrEqualTo(3);assertThat(number(r,"permanent_error_attempts")).isEqualTo(1);assertThat(number(r,"false_successes")).isZero();
 }
 @Test void backgroundTasksLeaveOneForegroundExecutionAndModelSlot(){
  var r=fixture.run("admission-isolation");processes(r);assertThat(number(r,"blocked_backgrounds")).isEqualTo(3);assertThat(number(r,"foreground_admission_ms")).isLessThanOrEqualTo(1000);assertThat(number(r,"foreground_model_admission_ms")).isLessThanOrEqualTo(1000);assertThat(number(r,"peak_active")).isLessThanOrEqualTo(4);assertThat(number(r,"peak_background")).isLessThanOrEqualTo(3);assertThat(number(r,"background_results_after_switch")).isEqualTo(3);
 }
}
