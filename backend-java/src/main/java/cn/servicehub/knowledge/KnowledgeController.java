package cn.servicehub.knowledge;
import cn.servicehub.auth.AuthController;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
@RestController
@Validated
@RequestMapping("/api/v1/knowledge/documents")
public class KnowledgeController {
    private final KnowledgeService documents;
    public KnowledgeController(KnowledgeService documents){this.documents=documents;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDtos.DocumentDto upload(HttpServletRequest request,@RequestPart("file")MultipartFile file,@RequestParam(defaultValue="") @Size(max=200)String title) throws IOException{
        try(var in=file.getInputStream()){return documents.upload(AuthController.actor(request),title,new UploadInput(file.getOriginalFilename(),file.getContentType(),in));}
    }
    @PostMapping(value="/{document_id}/versions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeDtos.DocumentDto version(HttpServletRequest request,@PathVariable("document_id")long id,@RequestPart("file")MultipartFile file)throws IOException{
        try(var in=file.getInputStream()){return documents.uploadVersion(AuthController.actor(request),id,new UploadInput(file.getOriginalFilename(),file.getContentType(),in));}
    }
    @GetMapping public KnowledgeDtos.DocumentPage list(HttpServletRequest request,@RequestParam(required=false) @Positive Long cursor,@RequestParam(defaultValue="30") @Min(1) @Max(100)int limit){return documents.list(AuthController.actor(request),cursor,limit);}
    @GetMapping("/{document_id}")public KnowledgeDtos.DocumentDto detail(HttpServletRequest request,@PathVariable("document_id")long id){return documents.get(AuthController.actor(request),id);}
    @PatchMapping("/{document_id}")public KnowledgeDtos.DocumentDto enable(HttpServletRequest request,@PathVariable("document_id")long id,@Valid @RequestBody KnowledgeDtos.EnableInput input){return documents.setEnabled(AuthController.actor(request),id,input.version(),input.enabled());}
    @DeleteMapping("/{document_id}")public ResponseEntity<Void> remove(HttpServletRequest request,@PathVariable("document_id")long id){documents.delete(AuthController.actor(request),id);return ResponseEntity.noContent().build();}
    @GetMapping("/{document_id}/download")public ResponseEntity<Resource> download(HttpServletRequest request,@PathVariable("document_id")long id,@RequestParam(name="version_id",required=false) @Positive Long version){
        var grant=documents.download(AuthController.actor(request),id,version);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(grant.mediaType())).contentLength(grant.size())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(grant.filename(),StandardCharsets.UTF_8).build().toString())
            .body(new FileSystemResource(grant.path()));
    }
}
