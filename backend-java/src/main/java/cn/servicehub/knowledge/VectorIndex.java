package cn.servicehub.knowledge;
import cn.servicehub.common.BusinessException;
import cn.servicehub.config.ServiceHubProperties;
import io.lettuce.core.*;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.*;
import io.lettuce.core.protocol.*;
import jakarta.annotation.PreDestroy;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;

/** Redis stages immutable generations; only MySQL's activated generation is searchable. */
@Service
public class VectorIndex implements AutoCloseable {
    public record Hit(long chunkId,double distance){}
    private final ServiceHubProperties properties;private final String index,prefix;
    private RedisClient client;private StatefulRedisConnection<String,String> connection;private boolean schemaReady;
    private enum Command implements ProtocolKeyword {
        CREATE("FT.CREATE"),SEARCH("FT.SEARCH"),INFO("FT.INFO");
        private final byte[] bytes;Command(String name){bytes=name.getBytes(StandardCharsets.US_ASCII);}
        @Override public byte[] getBytes(){return bytes;}
    }
    public VectorIndex(ServiceHubProperties properties){
        this.properties=properties;
        try{String signature=properties.model().embeddingName()+":"+properties.model().embeddingDimensions();
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(signature.getBytes(StandardCharsets.UTF_8))).substring(0,16);
            index="shj:idx:embedding:"+hash;prefix="shj:vector:"+hash+":";
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private synchronized StatefulRedisConnection<String,String> connection(){
        if(connection==null||!connection.isOpen()){
            var p=properties.redis();var uri=RedisURI.Builder.redis(p.host(),p.port()).withTimeout(Duration.ofSeconds(2));
            if(p.password()!=null&&!p.password().isBlank())uri.withPassword(p.password().toCharArray());
            client=RedisClient.create(uri.build());client.setOptions(ClientOptions.builder().protocolVersion(ProtocolVersion.RESP2).build());
            connection=client.connect(StringCodec.UTF8);schemaReady=false;
        }
        return connection;
    }
    private synchronized void ensure(){
        var commands=connection().sync();if(schemaReady)return;
        var args=args().add(index).add("ON").add("HASH").add("PREFIX").add(1).add(prefix)
            .add("SCHEMA").add("tenant_id").add("TAG").add("generation").add("TAG")
            .add("chunk_id").add("NUMERIC").add("embedding").add("VECTOR").add("HNSW").add(6)
            .add("TYPE").add("FLOAT32").add("DIM").add(properties.model().embeddingDimensions()).add("DISTANCE_METRIC").add("COSINE");
        try{commands.dispatch(Command.CREATE,new StatusOutput<String,String>(StringCodec.UTF8),args);}
        catch(RedisCommandExecutionException e){
            if(!e.getMessage().toLowerCase(Locale.ROOT).contains("index already exists"))throw e;
            var info=commands.dispatch(Command.INFO,new NestedMultiOutput<String,String>(StringCodec.UTF8),args().add(index));
            String descriptor=info.toString();
            if(!descriptor.contains("tenant_id")||!descriptor.contains("generation")||!descriptor.contains("HNSW")||!descriptor.contains("FLOAT32")||!descriptor.contains(String.valueOf(properties.model().embeddingDimensions())))throw new IllegalStateException("Incompatible vector index");
        }
        schemaReady=true;
    }
    public void write(long tenant,long version,long generation,long chunk,float[] vector){
        validate(vector,properties.model().embeddingDimensions());ensure();
        var args=args().add(key(tenant,version,generation,chunk)).add("tenant_id").add(tenant).add("generation").add(version+"_"+generation)
            .add("chunk_id").add(chunk).add("embedding").add(bytes(vector));
        connection().sync().dispatch(CommandType.HSET,new IntegerOutput<String,String>(StringCodec.UTF8),args);
    }
    public List<Hit> search(long tenant,float[] query,int limit,Map<Long,Long> generations){
        validate(query,properties.model().embeddingDimensions());if(limit<1||limit>30)throw new IllegalArgumentException("Vector limit must be 1-30");
        if(generations.isEmpty())return List.of();ensure();
        var values=generations.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e->{
            if(e.getKey()<=0||e.getValue()<=0)throw new IllegalArgumentException("Invalid activated generation");
            return e.getKey()+"_"+e.getValue();
        }).toList();
        String filter="(@tenant_id:{"+tenant+"} @generation:{"+String.join("|",values)+"})=>[KNN "+limit+" @embedding $vec AS distance]";
        var result=connection().sync().dispatch(Command.SEARCH,new NestedMultiOutput<String,String>(StringCodec.UTF8),args().add(index).add(filter)
            .add("PARAMS").add(2).add("vec").add(bytes(query)).add("SORTBY").add("distance").add("RETURN").add(2).add("chunk_id").add("distance").add("LIMIT").add(0).add(limit).add("DIALECT").add(2));
        var hits=new ArrayList<Hit>();
        for(int i=1;i+1<result.size();i+=2){
            if(!(result.get(i+1) instanceof List<?> fields))throw new IllegalStateException("Invalid vector search response");
            Long id=null;Double distance=null;
            for(int j=0;j+1<fields.size();j+=2){
                if("chunk_id".equals(fields.get(j)))id=Long.parseLong(String.valueOf(fields.get(j+1)));
                if("distance".equals(fields.get(j)))distance=Double.parseDouble(String.valueOf(fields.get(j+1)));
            }
            if(id==null||distance==null||!Double.isFinite(distance))throw new IllegalStateException("Invalid vector hit");
            hits.add(new Hit(id,distance));
        }
        hits.sort(Comparator.comparingDouble(Hit::distance).thenComparingLong(Hit::chunkId));return List.copyOf(hits);
    }
    public void deleteGeneration(long tenant,long version,long generation){
        var commands=connection().sync();String cursor="0";
        do{var page=commands.scan(ScanCursor.of(cursor),ScanArgs.Builder.matches(prefix+tenant+":"+version+":"+generation+":*").limit(100));cursor=page.getCursor();
            if(!page.getKeys().isEmpty())commands.del(page.getKeys().toArray(String[]::new));
        }while(!"0".equals(cursor));
    }
    static byte[] bytes(float[] vector){var data=ByteBuffer.allocate(vector.length*Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);for(float value:vector)data.putFloat(value);return data.array();}
    public static void validate(float[] vector,int dimensions){
        if(vector==null||vector.length!=dimensions)throw invalid();double norm=0;
        for(float value:vector){if(!Float.isFinite(value))throw invalid();norm+=(double)value*value;}
        if(norm==0)throw invalid();
    }
    private static BusinessException invalid(){return new BusinessException("invalid_embedding","向量模型返回了无效结果",422);}
    private String key(long tenant,long version,long generation,long chunk){if(tenant<=0||version<=0||generation<=0||chunk<=0)throw new IllegalArgumentException("Invalid vector scope");return prefix+tenant+":"+version+":"+generation+":"+chunk;}
    private CommandArgs<String,String> args(){return new CommandArgs<>(StringCodec.UTF8);}
    @PreDestroy @Override public synchronized void close(){if(connection!=null)connection.close();if(client!=null)client.shutdown();connection=null;client=null;schemaReady=false;}
}
