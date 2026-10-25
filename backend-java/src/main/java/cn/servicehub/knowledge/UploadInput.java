package cn.servicehub.knowledge;
import java.io.InputStream;
import java.util.Objects;
public record UploadInput(String filename,String mediaType,InputStream content) {
    public UploadInput {Objects.requireNonNull(content);}
}
