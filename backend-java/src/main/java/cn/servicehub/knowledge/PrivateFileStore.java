package cn.servicehub.knowledge;
import cn.servicehub.common.BusinessException;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
public final class PrivateFileStore {
    public record StoredUpload(String key,String filename,String mediaType,long size,String sha256){}
    private final Path root;private final int limit;
    public PrivateFileStore(Path root,int limit){
        if(root==null||!root.isAbsolute()||limit<1)throw new IllegalArgumentException("Absolute private root and positive limit required");
        this.root=root.normalize();this.limit=limit;
    }
    public Path resolve(String key){
        if(key==null||!key.matches("[a-f0-9]{32}\\.(txt|md|pdf)"))throw new IllegalArgumentException("Invalid private storage key");
        Path path=root.resolve(key).normalize();
        if(!path.startsWith(root)||Files.isSymbolicLink(path))throw new IllegalArgumentException("Private path escaped its root");
        try{
            if(Files.exists(path)&&!path.toRealPath().startsWith(root.toRealPath()))throw new IllegalArgumentException("Private path escaped its physical root");
        }catch(IOException e){throw unavailable();}
        return path;
    }
    public StoredUpload save(UploadInput upload){
        String name=Objects.requireNonNullElse(upload.filename(),"");
        if(name.isBlank()||name.length()>255||name.contains("/")||name.contains("\\")||name.contains(":")||name.chars().anyMatch(c->c<32))
            throw new BusinessException("invalid_filename","文件名不合法",422);
        int dot=name.lastIndexOf('.');String extension=dot<0?"":name.substring(dot).toLowerCase(Locale.ROOT);
        String media=switch(extension){case ".txt"->"text/plain";case ".md"->"text/markdown";case ".pdf"->"application/pdf";default->throw new BusinessException("unsupported_document","仅支持 UTF-8 TXT、Markdown 和文本 PDF",415);};
        String key=UUID.randomUUID().toString().replace("-","")+extension;Path path=resolve(key);long size=0;
        try{
            Files.createDirectories(root);var digest=MessageDigest.getInstance("SHA-256");
            try(var out=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
                byte[] buffer=new byte[262144];int read;
                while((read=upload.content().read(buffer))!=-1){
                    size+=read;if(size>limit)throw new BusinessException("upload_too_large","单个文件不能超过 10 MiB",413);
                    digest.update(buffer,0,read);out.write(buffer,0,read);
                }
            }
            if(size==0)throw new BusinessException("empty_upload","文件内容为空",422);
            if(media.equals("application/pdf")){
                try(var in=Files.newInputStream(path)){if(!Arrays.equals(in.readNBytes(5),"%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new BusinessException("invalid_pdf","PDF 文件内容不合法",422);}
            }
            return new StoredUpload(key,name,media,size,HexFormat.of().formatHex(digest.digest()));
        }catch(BusinessException e){deleteOwned(key);throw e;}
        catch(IOException|NoSuchAlgorithmException e){deleteOwned(key);throw unavailable();}
    }
    /** Only a generated key under this store can be removed, never a caller-provided path. */
    public void deleteOwned(String key){
        try{Files.deleteIfExists(resolve(key));}catch(IOException e){throw unavailable();}
    }
    private BusinessException unavailable(){return new BusinessException("file_unavailable","文档文件暂时不可用",503);}
}
