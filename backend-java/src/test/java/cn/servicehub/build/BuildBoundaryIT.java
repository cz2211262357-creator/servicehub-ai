package cn.servicehub.build;
import cn.servicehub.support.SystemScenarioAccess;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import static cn.servicehub.support.SystemScenarioAccess.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildBoundaryIT {
 final SystemScenarioAccess fixture=new SystemScenarioAccess();
 @AfterAll void close(){fixture.close();}
 @Test void threeWorkingDirectoriesKeepDistributionAndRepositoryInsideProject(){
  var r=fixture.run("maven-boundary");assertThat(number(r,"verified_cwds")).isEqualTo(3);assertThat(number(r,"external_managed_paths")).isZero();assertThat(number(r,"overwritten_worktrees")).isZero();
 }
 @Test void realDockerBuildExcludesPrivateSentinelsAndOnlyBindsLoopback(){
  var r=fixture.run("docker-boundary");assertThat(number(r,"sentinels_tested")).isGreaterThanOrEqualTo(4);assertThat(number(r,"sentinels_in_image")).isZero();assertThat(number(r,"non_loopback_ports")).isZero();assertThat(number(r,"foreign_containers_modified")).isZero();
 }
}
