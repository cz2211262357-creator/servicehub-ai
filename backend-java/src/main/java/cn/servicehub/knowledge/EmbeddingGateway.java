package cn.servicehub.knowledge;
import cn.servicehub.ai.CancellationGuard;
import java.util.List;
public interface EmbeddingGateway { List<float[]> embed(List<String> texts,CancellationGuard guard); }
