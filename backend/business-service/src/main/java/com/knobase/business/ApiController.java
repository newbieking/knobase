package com.knobase.business;

import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.knobase.business.ApiModels.*;

/** No SSO or security facade: all routes belong to the loopback-only local demo administrator. */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final WorkspaceService service;

    public ApiController(WorkspaceService service) { this.service = service; }

    @GetMapping("/bootstrap")
    public Bootstrap bootstrap() { return service.bootstrap(); }

    @GetMapping("/health")
    public Health health() { return service.health(); }

    @PostMapping("/knowledge-bases")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase createKnowledgeBase(@Valid @RequestBody CreateKnowledgeBase request) {
        return service.createKnowledgeBase(request);
    }

    @PatchMapping("/knowledge-bases/{id}")
    public KnowledgeBase patchKnowledgeBase(@PathVariable String id, @Valid @RequestBody PatchKnowledgeBase request) {
        return service.patchKnowledgeBase(id, request);
    }

    @DeleteMapping("/knowledge-bases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteKnowledgeBase(@PathVariable String id) { service.deleteKnowledgeBase(id); }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<Document> upload(@RequestParam("kbId") String kbId, @RequestParam("files") List<MultipartFile> files) {
        return service.upload(kbId, files);
    }

    @PostMapping("/documents/text")
    @ResponseStatus(HttpStatus.CREATED)
    public Document importText(@Valid @RequestBody ImportText request) { return service.importText(request); }

    @DeleteMapping("/documents/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable String id) { service.deleteDocument(id); }

    @PostMapping("/documents/{id}/reindex")
    public Document reindex(@PathVariable String id) { return service.reindex(id); }

    @GetMapping("/documents/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        Document doc = service.document(id);
        int extension = doc.name().lastIndexOf('.');
        String filename = (extension > 0 ? doc.name().substring(0, extension) : doc.name()) + ".txt";
        byte[] bytes = doc.content().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length).body(bytes);
    }

    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) { return service.chat(request); }

    @GetMapping("/sessions")
    public List<Session> sessions() { return service.sessions(); }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable String id) { service.deleteSession(id); }

    @PutMapping("/settings")
    public Settings settings(@Valid @RequestBody Settings request) { return service.saveSettings(request); }
}
