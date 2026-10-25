package cn.servicehub.knowledge;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class PrivateFileStoreTest {
    @TempDir Path root;
    Object store(){
        try{return Class.forName("cn.servicehub.knowledge.PrivateFileStore").getConstructor(Path.class,int.class).newInstance(root,10485760);}
        catch(ReflectiveOperationException e){throw new AssertionError("PrivateFileStore not implemented",e);}
    }
    Object save(String name,String media,byte[] body){
        try{
            var input=Class.forName("cn.servicehub.knowledge.UploadInput").getConstructor(String.class,String.class,InputStream.class).newInstance(name,media,new ByteArrayInputStream(body));
            return call(store(),"save",new Class<?>[]{input.getClass()},input);
        }catch(ReflectiveOperationException e){throw new AssertionError("UploadInput not implemented",e);}
    }
    static Object call(Object bean,String method,Class<?>[] types,Object...args){
        try{var m=bean.getClass().getMethod(method,types);m.trySetAccessible();return m.invoke(bean,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError("Missing contract "+method,e);}
    }
    static Object field(Object object,String name){return call(object,name,new Class<?>[0]);}
    @Test void storesRandomPrivateNameAndDigestWithoutTrustingClientPath() throws Exception{
        byte[] body="中文售后政策".getBytes(StandardCharsets.UTF_8);var saved=save("政策.txt","text/plain",body);
        String key=String.valueOf(field(saved,"key"));assertThat(key).matches("[a-f0-9]{32}\\.txt").doesNotContain("政策");
        assertThat(field(saved,"filename")).isEqualTo("政策.txt");assertThat(((Number)field(saved,"size")).longValue()).isEqualTo(body.length);
        assertThat(field(saved,"sha256")).isEqualTo(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body)));
        assertThat(Files.readAllBytes((Path)call(store(),"resolve",new Class<?>[]{String.class},key))).isEqualTo(body);
    }
    @Test void traversalAndAbsoluteStorageKeysAreRejected(){
        for(String key:new String[]{"../.env","C:\\Windows\\secret.txt","/etc/passwd","abc.txt"})
            assertThatThrownBy(()->call(store(),"resolve",new Class<?>[]{String.class},key)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void filenamesContainingDirectoriesAreRejected(){
        for(String name:new String[]{"../escape.txt","C:\\escape.txt","folder/file.txt"})
            assertThatThrownBy(()->save(name,"text/plain",new byte[]{1})).isInstanceOf(RuntimeException.class);
    }
    @Test void tenMiBBoundaryIsAcceptedButOneMoreByteLeavesNoPartialFile() throws Exception{
        byte[] body=new byte[10485760];java.util.Arrays.fill(body,(byte)'x');
        assertThat(((Number)field(save("boundary.txt","text/plain",body),"size")).longValue()).isEqualTo(10485760L);
        long before;try(var files=Files.list(root)){before=files.count();}
        assertThatThrownBy(()->save("oversized.txt","text/plain",new byte[10485761])).isInstanceOf(RuntimeException.class);
        try(var files=Files.list(root)){assertThat(files.count()).isEqualTo(before);}
    }
    @Test void emptyUploadUnsupportedExtensionAndFakePdfAreRejected(){
        assertThatThrownBy(()->save("empty.txt","text/plain",new byte[0])).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->save("payload.exe","application/octet-stream",new byte[]{1})).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->save("fake.pdf","application/pdf","not a pdf".getBytes(StandardCharsets.UTF_8))).isInstanceOf(RuntimeException.class);
    }
    @Test void blankOrBinaryTextDoesNotBecomeKnowledge() throws Exception{
        Path file=root.resolve("blank.txt");Files.writeString(file," \n\t ");
        Object parser;
        try{parser=Class.forName("cn.servicehub.knowledge.DocumentParser").getConstructor().newInstance();}
        catch(ReflectiveOperationException e){throw new AssertionError("DocumentParser not implemented",e);}
        assertThatThrownBy(()->call(parser,"parse",new Class<?>[]{Path.class,String.class},file,"text/plain")).isInstanceOf(RuntimeException.class);
        Files.write(file,new byte[]{0,1,2});
        assertThatThrownBy(()->call(parser,"parse",new Class<?>[]{Path.class,String.class},file,"text/plain")).isInstanceOf(RuntimeException.class);
    }
}
