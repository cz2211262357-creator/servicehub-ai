package cn.servicehub.knowledge;
import cn.servicehub.common.BusinessException;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
@Service
public class DocumentParser {
    public record TextPage(int pageNumber,String text){}
    public record TextChunk(int pageNumber,String text){}
    public List<TextPage> parse(Path path,String mediaType){
        try{
            if("application/pdf".equals(mediaType)){
                try(var document=Loader.loadPDF(path.toFile())){
                    if(document.isEncrypted()||document.getNumberOfPages()>500)throw invalid("pdf_not_supported");
                    var pages=new ArrayList<TextPage>();var stripper=new PDFTextStripper();int total=0;
                    for(int page=1;page<=document.getNumberOfPages();page++){
                        if(Thread.currentThread().isInterrupted())throw new cn.servicehub.ai.ExecutionCancelledException("parse_interrupted");
                        stripper.setStartPage(page);stripper.setEndPage(page);String text=stripper.getText(document).strip();total+=text.length();
                        if(total>10485760)throw invalid("document_too_large");
                        if(!text.isEmpty())pages.add(new TextPage(page,text));
                    }
                    if(pages.isEmpty())throw invalid("pdf_no_text");return List.copyOf(pages);
                }catch(InvalidPasswordException e){throw invalid("pdf_not_supported");}
                catch(IOException e){throw invalid("invalid_pdf");}
            }
            if(!Set.of("text/plain","text/markdown").contains(mediaType))throw invalid("invalid_text");
            byte[] bytes=Files.readAllBytes(path);
            for(byte b:bytes)if(b==0)throw invalid("invalid_text");
            if(bytes.length>=5&&new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))throw invalid("invalid_text");
            String text;
            try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
            catch(CharacterCodingException e){throw invalid("invalid_utf8");}
            if(text.startsWith("\uFEFF"))text=text.substring(1);text=text.strip();
            if(text.isEmpty())throw invalid("empty_document");return List.of(new TextPage(1,text));
        }catch(IOException e){throw new BusinessException("source_file_unavailable","文档源文件暂时不可用",503);}
    }
    public List<TextChunk> split(List<TextPage> pages){
        var chunks=new ArrayList<TextChunk>();
        for(var page:pages){
            int[] points=page.text().codePoints().toArray();
            for(int offset=0;offset<points.length;offset+=700){
                String text=new String(points,offset,Math.min(800,points.length-offset)).strip();
                if(!text.isEmpty())chunks.add(new TextChunk(page.pageNumber(),text));
            }
        }
        return List.copyOf(chunks);
    }
    private BusinessException invalid(String code){return new BusinessException(code,"文档无法解析为受支持的文本",422);}
}
