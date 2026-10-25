package cn.servicehub.support;
import org.testcontainers.containers.GenericContainer;
/** Reads live channel state, not Quorum's periodically cached queue statistics.
 * All channels belong to this disposable fixture, which retires old scenarios first. */
public final class BrokerObservation {
 private final GenericContainer<?> broker;
 public BrokerObservation(GenericContainer<?> broker){this.broker=broker;}
 public int unacknowledgedDeliveries()throws Exception{
  var report=broker.execInContainer("rabbitmqctl","list_channels","messages_unacknowledged","--quiet");
  if(report.getExitCode()!=0)throw new AssertionError("Owned broker observation failed");
  var counts=report.getStdout().lines().map(String::strip).filter(line->line.matches("[0-9]+"))
   .mapToInt(Integer::parseInt).toArray();
  if(counts.length==0)throw new AssertionError("Owned broker has no observable open channel");
  return java.util.Arrays.stream(counts).sum();
 }
}
