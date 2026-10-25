package cn.servicehub.knowledge;
import cn.servicehub.auth.AuthorizationService;
import cn.servicehub.common.Actor;
import cn.servicehub.config.ServiceHubProperties;
import cn.servicehub.jobs.*;
import cn.servicehub.operations.AuditService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL commit followed by a lost acknowledgement at the transaction boundary. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadCommitOutcomeIT {
 final KnowledgeFixture f=new KnowledgeFixture();
 @BeforeAll void start() throws Exception {f.start(Map.of("servicehub.upload-directory",f.root.resolve(".data/java/custom-uploads").toString()));}
 @BeforeEach void reset() throws Exception {f.reset();}
 @AfterAll void close(){f.close();}
 @Test void unknownCommitOutcomeMustNeverDeleteACommittedSourceFile() throws Exception {
  var context=f.app.context;
  var manager=new DataSourceTransactionManager(context.getBean(DataSource.class)){
   @Override protected void doCommit(DefaultTransactionStatus status){super.doCommit(status);throw new TransactionSystemException("Synthetic lost COMMIT acknowledgement");}
  };
  var service=new KnowledgeService(context.getBean(KnowledgeMapper.class),context.getBean(PrivateFileStore.class),context.getBean(AuthorizationService.class),context.getBean(JobJournal.class),context.getBean(JobCoordinator.class),context.getBean(ServiceHubProperties.class),manager,context.getBean(AuditService.class));
  byte[] content="原文在提交应答丢失后仍必须可下载".getBytes(StandardCharsets.UTF_8);
  Actor actor=new Actor(1001,2001,cn.servicehub.auth.Role.ADMIN,1);
  assertThatThrownBy(()->service.upload(actor,"Commit uncertainty",new UploadInput("commit.txt","text/plain",new ByteArrayInputStream(content)))).isInstanceOf(TransactionSystemException.class);
  var row=f.app.db.queryForMap("SELECT id,document_id,storage_key,job_id FROM document_versions WHERE filename='commit.txt'");
  assertThat(row.get("job_id")).isNotNull();
  var path=context.getBean(PrivateFileStore.class).resolve((String)row.get("storage_key"));
  assertThat(path).isRegularFile();assertThat(Files.readAllBytes(path)).isEqualTo(content);
  var download=f.app.request("GET","/api/v1/knowledge/documents/"+row.get("document_id")+"/download",null,f.admin);
  assertThat(download.statusCode()).as(download.body()).isEqualTo(200);
  assertThat(download.body()).isEqualTo(new String(content,StandardCharsets.UTF_8));
 }
 @Test void aConfirmedRollbackStillCleansItsOwnNewFile() throws Exception {
  var files=f.app.context.getBean(PrivateFileStore.class);
  var marker=files.save(new UploadInput("marker.txt","text/plain",new ByteArrayInputStream(new byte[]{1})));
  var directory=files.resolve(marker.key()).getParent();
  long before;try(var paths=Files.list(directory)){before=paths.count();}
  var response=f.upload("/api/v1/knowledge/documents/999999/versions","rollback.txt","text/plain",new byte[]{2,3},null,f.admin);
  assertThat(response.statusCode()).isEqualTo(404);
  try(var paths=Files.list(directory)){assertThat(paths.count()).isEqualTo(before);}
  assertThat(files.resolve(marker.key())).isRegularFile();
 }

 @Test void configuredUploadDirectoryIsSharedByWritesAndDownloads() throws Exception {
  var document=f.upload("自定义私有目录仍可下载");long id=document.path("id").asLong();
  String key=f.app.db.queryForObject("SELECT storage_key FROM document_versions WHERE document_id=?",String.class,id);
  assertThat(f.root.resolve(".data/java/custom-uploads").resolve(key)).isRegularFile();
  assertThat(f.root.resolve(".data/java/uploads").resolve(key)).doesNotExist();
  var response=f.app.request("GET","/api/v1/knowledge/documents/"+id+"/download",null,f.admin);
  assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEqualTo("自定义私有目录仍可下载");
 }
}
