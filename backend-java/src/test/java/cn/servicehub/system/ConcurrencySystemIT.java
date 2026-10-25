package cn.servicehub.system;
import cn.servicehub.support.SystemScenarioAccess;
import org.junit.jupiter.api.*;
import static cn.servicehub.support.SystemScenarioAccess.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrencySystemIT {
 final SystemScenarioAccess fixture=new SystemScenarioAccess();
 @AfterAll void close(){fixture.close();}
 @Test void duplicateMessagesAcrossTwoApiJvmsHaveOneAtomicAcceptance(){
  var result=fixture.run("duplicate-message");processes(result);
  for(var row:rounds(result)){assertThat(number(row,"message_count")).isEqualTo(1);assertThat(number(row,"run_count")).isEqualTo(1);assertThat(number(row,"job_count")).isEqualTo(1);assertThat(number(row,"epoch_delta")).isEqualTo(1);assertThat(number(row,"distinct_receipts")).isEqualTo(1);assertThat(number(row,"gate_hits")).isGreaterThanOrEqualTo(20);}
 }
 @Test void conflictsAndInjectedTransactionFailuresCannotAdvanceHalfAConversation(){
  var result=fixture.run("message-atomicity");processes(result);
  for(var row:rounds(result)){assertThat(number(row,"conflict_status")).isEqualTo(409);assertThat(number(row,"rollback_rows")).isZero();assertThat(number(row,"rollback_epoch_delta")).isZero();assertThat(number(row,"injection_hits")).isGreaterThanOrEqualTo(2);}
 }
 @Test void twoPagesSwitchOnlyOnceAndOldStopReceiptCannotCancelNewWork(){
  var result=fixture.run("switch-race");processes(result);
  for(var row:rounds(result)){assertThat(number(row,"success_count")).isEqualTo(1);assertThat(number(row,"conflict_count")).isEqualTo(1);assertThat(number(row,"new_task_cancelled_by_old_stop")).isZero();assertThat(number(row,"late_a1_commits")).isZero();}
 }
 @Test void twentyConfirmationsAndTwentyClaimsHaveExactlyOneWinner(){
  var result=fixture.run("confirmation-claim");processes(result);
  for(var row:rounds(result)){assertThat(number(row,"ticket_count")).isEqualTo(1);assertThat(number(row,"claim_winners")).isEqualTo(1);assertThat(number(row,"confirmation_requests")).isEqualTo(20);assertThat(number(row,"claim_requests")).isEqualTo(20);}
 }
 @Test void revocationBeforeFinalCommitPreventsAllNewBusinessWrites(){
  var result=fixture.run("revoke-first");processes(result);
  for(var row:rounds(result)){assertThat(number(row,"positive_control_drafts")).isEqualTo(1);assertThat(number(row,"provider_gate_hits")).isPositive();assertThat(number(row,"unauthorized_answers")).isZero();assertThat(number(row,"unauthorized_drafts")).isZero();}
 }
 @Test void legitimateWriteCanCommitBeforeWaitingRevocationButNeverAfterIt(){
  var result=fixture.run("business-first");processes(result);
  for(var row:rounds(result)){assertThat(number(row,"transaction_gate_hits")).isPositive();assertThat(number(row,"revocation_finished_before_release")).isZero();assertThat(number(row,"legitimate_writes")).isEqualTo(1);assertThat(number(row,"protected_writes_after_revocation")).isZero();}
 }
}
